package com.osrstcgbattles.backend.ranked;

import com.osrstcgbattles.backend.rating.Glicko2Calculator;
import com.osrstcgbattles.backend.rating.Glicko2Rating;
import com.osrstcgbattles.backend.rating.Outcome;
import com.osrstcgbattles.backend.rating.RatingPeriodGame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** SQLite-backed identity, authentication, rating, and match-history store. */
public final class RankedRepository implements AutoCloseable
{
	private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,12}");
	private static final int BUSY_TIMEOUT_MILLIS = 5_000;
	private static final int MAX_MATCH_ID_LENGTH = 128;

	private final Connection connection;
	private final Glicko2Calculator calculator;
	private boolean closed;
	private boolean writeHealthy = true;

	public RankedRepository(Path databasePath)
	{
		this(databasePath, new Glicko2Calculator());
	}

	public RankedRepository(Path databasePath, Glicko2Calculator calculator)
	{
		Objects.requireNonNull(databasePath, "databasePath");
		this.calculator = Objects.requireNonNull(calculator, "calculator");
		Connection opened = null;
		try
		{
			Path absolutePath = databasePath.toAbsolutePath().normalize();
			Path parent = absolutePath.getParent();
			if (parent != null) Files.createDirectories(parent);
			opened = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
			connection = opened;
			configure();
			initializeSchema();
			migrateNormalizedIgns();
			recoverPendingMatches();
		}
		catch (IOException | SQLException exception)
		{
			if (opened != null)
			{
				try { opened.close(); } catch (SQLException suppressed) { exception.addSuppressed(suppressed); }
			}
			throw new RankedPersistenceException("Could not open ranked database", exception);
		}
		catch (RuntimeException exception)
		{
			if (opened != null)
			{
				try { opened.close(); } catch (SQLException suppressed) { exception.addSuppressed(suppressed); }
			}
			throw exception;
		}
	}

	/** Validates and normalizes an OSRS display name for identity lookup. */
	public static String normalizeIgn(String displayName)
	{
		Objects.requireNonNull(displayName, "displayName");
		if (!DISPLAY_NAME.matcher(displayName).matches() || !displayName.equals(displayName.trim()))
		{
			throw new IllegalArgumentException("OSRS display name must be 1-12 letters, digits, spaces, hyphens, or underscores");
		}
		String normalized = displayName.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll(" +", " ");
		if (!normalized.equals(normalized.trim()))
			throw new IllegalArgumentException("OSRS display name cannot start or end with a separator");
		return normalized;
	}

	/** Returns the existing account or creates its immutable identity on first use of the IGN. */
	public synchronized RankedAccount authenticateOrCreate(String displayName, byte[] x509PublicKey)
	{
		String normalizedIgn = normalizeIgn(displayName);
		byte[] key = validatePublicKey(x509PublicKey);
		return inTransaction(() ->
		{
			RankedAccount existing = findByNormalizedIgn(normalizedIgn);
			if (existing != null)
			{
				requireMatchingKey(existing, key);
				return existing;
			}

			UUID accountId = UUID.randomUUID();
			Glicko2Rating rating = Glicko2Rating.unrated();
			try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ranked_accounts(account_id, display_name, normalized_ign, public_key, rating, rating_deviation, volatility, games_played, wins, losses, draws, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))
			{
				statement.setString(1, accountId.toString());
				statement.setString(2, displayName);
				statement.setString(3, normalizedIgn);
				statement.setBytes(4, key);
				setRating(statement, 5, rating);
				statement.setLong(12, System.currentTimeMillis());
				statement.executeUpdate();
			}
			return new RankedAccount(accountId, displayName, normalizedIgn, key, rating);
		});
	}

	public synchronized Optional<RankedAccount> findAccount(String displayName)
	{
		ensureOpen();
		try
		{
			return Optional.ofNullable(findByNormalizedIgn(normalizeIgn(displayName)));
		}
		catch (SQLException exception)
		{
			throw failure("Could not look up ranked account", exception);
		}
	}

	/** Returns false for an unknown IGN or a key that does not match its first-use key. */
	public synchronized boolean authenticate(String displayName, byte[] x509PublicKey)
	{
		byte[] key = validatePublicKey(x509PublicKey);
		return findAccount(displayName)
			.map(account -> MessageDigest.isEqual(account.publicKey(), key))
			.orElse(false);
	}

	public synchronized Glicko2Rating getRating(UUID accountId)
	{
		ensureOpen();
		try
		{
			return requireAccount(Objects.requireNonNull(accountId, "accountId")).rating();
		}
		catch (SQLException exception)
		{
			throw failure("Could not retrieve rating", exception);
		}
	}

	public synchronized boolean isHealthy()
	{
		if (closed || !writeHealthy) return false;
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery("SELECT 1"))
		{
			return result.next() && result.getInt(1) == 1;
		}
		catch (SQLException exception)
		{
			return false;
		}
	}

	public synchronized boolean hasPendingMatch(UUID accountId)
	{
		ensureOpen();
		Objects.requireNonNull(accountId, "accountId");
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT 1 FROM ranked_pending_matches WHERE player_one_id = ? OR player_two_id = ? LIMIT 1"))
		{
			statement.setString(1, accountId.toString());
			statement.setString(2, accountId.toString());
			try (ResultSet result = statement.executeQuery()) { return result.next(); }
		}
		catch (SQLException exception)
		{
			throw failure("Could not inspect pending ranked matches", exception);
		}
	}

	/** Atomically inserts one match and updates both ratings from their pre-match values. */
	public synchronized MatchRecording recordCompletedMatch(
		String matchId, UUID playerOneId, UUID playerTwoId, Outcome playerOneOutcome)
	{
		validateMatch(matchId, playerOneId, playerTwoId, playerOneOutcome);
		MatchRecording existing = inTransaction(() ->
		{
			RankedMatch completed = findMatch(matchId);
			if (completed != null)
			{
				requireSameMatch(completed, playerOneId, playerTwoId, playerOneOutcome);
				return new MatchRecording(completed, false);
			}
			PendingMatch pending = findPendingMatch(matchId);
			if (pending == null)
			{
				requireAccount(playerOneId);
				requireAccount(playerTwoId);
				insertPendingMatch(matchId, playerOneId, playerTwoId, playerOneOutcome, System.currentTimeMillis());
			}
			else pending.requireSame(playerOneId, playerTwoId, playerOneOutcome);
			return null;
		});
		return existing != null ? existing : applyPendingMatch(matchId);
	}

	/** Returns newest matches first. */
	public synchronized List<RankedMatch> getMatchHistory(UUID accountId, int limit)
	{
		ensureOpen();
		Objects.requireNonNull(accountId, "accountId");
		if (limit < 1) throw new IllegalArgumentException("limit must be positive");
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT * FROM ranked_matches WHERE player_one_id = ? OR player_two_id = ? ORDER BY completed_at DESC, match_id DESC LIMIT ?"))
		{
			statement.setString(1, accountId.toString());
			statement.setString(2, accountId.toString());
			statement.setInt(3, limit);
			try (ResultSet results = statement.executeQuery())
			{
				List<RankedMatch> matches = new ArrayList<>();
				while (results.next()) matches.add(readMatch(results));
				return List.copyOf(matches);
			}
		}
		catch (SQLException exception)
		{
			throw failure("Could not retrieve match history", exception);
		}
	}

	@Override
	public synchronized void close()
	{
		if (closed) return;
		closed = true;
		try
		{
			connection.close();
		}
		catch (SQLException exception)
		{
			throw failure("Could not close ranked database", exception);
		}
	}

	private void configure() throws SQLException
	{
		try (Statement statement = connection.createStatement())
		{
			statement.execute("PRAGMA foreign_keys = ON");
			statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
			statement.execute("PRAGMA journal_mode = WAL");
		}
	}

	private void initializeSchema() throws SQLException
	{
		try (Statement statement = connection.createStatement())
		{
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS ranked_accounts ("
				+ "account_id TEXT PRIMARY KEY NOT NULL, display_name TEXT NOT NULL, normalized_ign TEXT NOT NULL UNIQUE, public_key BLOB NOT NULL, "
				+ "rating REAL NOT NULL, rating_deviation REAL NOT NULL CHECK(rating_deviation > 0), volatility REAL NOT NULL CHECK(volatility > 0), "
				+ "games_played INTEGER NOT NULL CHECK(games_played >= 0), wins INTEGER NOT NULL CHECK(wins >= 0), losses INTEGER NOT NULL CHECK(losses >= 0), "
				+ "draws INTEGER NOT NULL CHECK(draws >= 0), created_at INTEGER NOT NULL, CHECK(games_played = wins + losses + draws))");
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS ranked_matches ("
				+ "match_id TEXT PRIMARY KEY NOT NULL, player_one_id TEXT NOT NULL REFERENCES ranked_accounts(account_id), "
				+ "player_two_id TEXT NOT NULL REFERENCES ranked_accounts(account_id), player_one_outcome TEXT NOT NULL CHECK(player_one_outcome IN ('WIN','LOSS','DRAW')), "
				+ "completed_at INTEGER NOT NULL, p1_before_rating REAL NOT NULL, p1_before_rd REAL NOT NULL, p1_before_vol REAL NOT NULL, "
				+ "p1_before_games INTEGER NOT NULL, p1_before_wins INTEGER NOT NULL, p1_before_losses INTEGER NOT NULL, p1_before_draws INTEGER NOT NULL, "
				+ "p1_after_rating REAL NOT NULL, p1_after_rd REAL NOT NULL, p1_after_vol REAL NOT NULL, p1_after_games INTEGER NOT NULL, "
				+ "p1_after_wins INTEGER NOT NULL, p1_after_losses INTEGER NOT NULL, p1_after_draws INTEGER NOT NULL, "
				+ "p2_before_rating REAL NOT NULL, p2_before_rd REAL NOT NULL, p2_before_vol REAL NOT NULL, p2_before_games INTEGER NOT NULL, "
				+ "p2_before_wins INTEGER NOT NULL, p2_before_losses INTEGER NOT NULL, p2_before_draws INTEGER NOT NULL, "
				+ "p2_after_rating REAL NOT NULL, p2_after_rd REAL NOT NULL, p2_after_vol REAL NOT NULL, p2_after_games INTEGER NOT NULL, "
				+ "p2_after_wins INTEGER NOT NULL, p2_after_losses INTEGER NOT NULL, p2_after_draws INTEGER NOT NULL, CHECK(player_one_id <> player_two_id))");
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS ranked_pending_matches ("
				+ "match_id TEXT PRIMARY KEY NOT NULL, player_one_id TEXT NOT NULL REFERENCES ranked_accounts(account_id), "
				+ "player_two_id TEXT NOT NULL REFERENCES ranked_accounts(account_id), player_one_outcome TEXT NOT NULL CHECK(player_one_outcome IN ('WIN','LOSS','DRAW')), "
				+ "completed_at INTEGER NOT NULL, CHECK(player_one_id <> player_two_id))");
			statement.executeUpdate("CREATE INDEX IF NOT EXISTS ranked_matches_player_one ON ranked_matches(player_one_id, completed_at DESC)");
			statement.executeUpdate("CREATE INDEX IF NOT EXISTS ranked_matches_player_two ON ranked_matches(player_two_id, completed_at DESC)");
		}
	}

	private void migrateNormalizedIgns() throws SQLException
	{
		Map<String, String> canonicalOwners = new java.util.HashMap<>();
		List<String[]> updates = new ArrayList<>();
		try (Statement statement = connection.createStatement();
			 ResultSet results = statement.executeQuery("SELECT account_id, display_name, normalized_ign FROM ranked_accounts"))
		{
			while (results.next())
			{
				String accountId = results.getString("account_id");
				String canonical = normalizeIgn(results.getString("display_name"));
				String previous = canonicalOwners.putIfAbsent(canonical, accountId);
				if (previous != null && !previous.equals(accountId))
					throw new SQLException("ranked accounts collide after IGN canonicalization");
				if (!canonical.equals(results.getString("normalized_ign")))
					updates.add(new String[]{canonical, accountId});
			}
		}
		try (PreparedStatement statement = connection.prepareStatement(
			"UPDATE ranked_accounts SET normalized_ign = ? WHERE account_id = ?"))
		{
			for (String[] update : updates)
			{
				statement.setString(1, update[0]);
				statement.setString(2, update[1]);
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	private void recoverPendingMatches() throws SQLException
	{
		List<String> pending = new ArrayList<>();
		try (Statement statement = connection.createStatement();
			 ResultSet results = statement.executeQuery("SELECT match_id FROM ranked_pending_matches ORDER BY completed_at, match_id"))
		{
			while (results.next()) pending.add(results.getString(1));
		}
		for (String matchId : pending) applyPendingMatch(matchId);
	}

	private MatchRecording applyPendingMatch(String matchId)
	{
		return inTransaction(() ->
		{
			RankedMatch existing = findMatch(matchId);
			if (existing != null)
			{
				deletePendingMatch(matchId);
				return new MatchRecording(existing, false);
			}
			PendingMatch pending = findPendingMatch(matchId);
			if (pending == null) throw new IllegalStateException("pending ranked match was not found");
			Glicko2Rating firstBefore = requireAccount(pending.playerOneId).rating();
			Glicko2Rating secondBefore = requireAccount(pending.playerTwoId).rating();
			Glicko2Rating firstAfter = calculator.calculate(firstBefore,
				new RatingPeriodGame(secondBefore, pending.outcome));
			Glicko2Rating secondAfter = calculator.calculate(secondBefore,
				new RatingPeriodGame(firstBefore, opposite(pending.outcome)));
			insertMatch(matchId, pending.playerOneId, pending.playerTwoId, pending.outcome, pending.completedAt,
				firstBefore, firstAfter, secondBefore, secondAfter);
			updateRating(pending.playerOneId, firstAfter);
			updateRating(pending.playerTwoId, secondAfter);
			deletePendingMatch(matchId);
			RankedMatch match = new RankedMatch(matchId, pending.playerOneId, pending.playerTwoId, pending.outcome,
				Instant.ofEpochMilli(pending.completedAt), firstBefore, firstAfter, secondBefore, secondAfter);
			return new MatchRecording(match, true);
		});
	}

	private RankedAccount findByNormalizedIgn(String normalizedIgn) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ranked_accounts WHERE normalized_ign = ?"))
		{
			statement.setString(1, normalizedIgn);
			try (ResultSet results = statement.executeQuery())
			{
				return results.next() ? readAccount(results) : null;
			}
		}
	}

	private RankedAccount requireAccount(UUID accountId) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ranked_accounts WHERE account_id = ?"))
		{
			statement.setString(1, accountId.toString());
			try (ResultSet results = statement.executeQuery())
			{
				if (!results.next()) throw new IllegalArgumentException("ranked account was not found");
				return readAccount(results);
			}
		}
	}

	private static RankedAccount readAccount(ResultSet results) throws SQLException
	{
		return new RankedAccount(UUID.fromString(results.getString("account_id")), results.getString("display_name"),
			results.getString("normalized_ign"), results.getBytes("public_key"), readRating(results, ""));
	}

	private RankedMatch findMatch(String matchId) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM ranked_matches WHERE match_id = ?"))
		{
			statement.setString(1, matchId);
			try (ResultSet results = statement.executeQuery())
			{
				return results.next() ? readMatch(results) : null;
			}
		}
	}

	private PendingMatch findPendingMatch(String matchId) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement(
			"SELECT player_one_id, player_two_id, player_one_outcome, completed_at FROM ranked_pending_matches WHERE match_id = ?"))
		{
			statement.setString(1, matchId);
			try (ResultSet results = statement.executeQuery())
			{
				return results.next() ? new PendingMatch(UUID.fromString(results.getString(1)),
					UUID.fromString(results.getString(2)), Outcome.valueOf(results.getString(3)), results.getLong(4)) : null;
			}
		}
	}

	private void insertPendingMatch(String matchId, UUID first, UUID second, Outcome outcome, long completedAt)
		throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement(
			"INSERT INTO ranked_pending_matches(match_id, player_one_id, player_two_id, player_one_outcome, completed_at) VALUES (?, ?, ?, ?, ?)"))
		{
			statement.setString(1, matchId);
			statement.setString(2, first.toString());
			statement.setString(3, second.toString());
			statement.setString(4, outcome.name());
			statement.setLong(5, completedAt);
			statement.executeUpdate();
		}
	}

	private void deletePendingMatch(String matchId) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement(
			"DELETE FROM ranked_pending_matches WHERE match_id = ?"))
		{
			statement.setString(1, matchId);
			statement.executeUpdate();
		}
	}

	private static void requireSameMatch(RankedMatch match, UUID first, UUID second, Outcome outcome)
	{
		if (!match.playerOneId().equals(first) || !match.playerTwoId().equals(second)
			|| match.playerOneOutcome() != outcome)
			throw new IllegalArgumentException("matchId is already recorded with different details");
	}

	private static RankedMatch readMatch(ResultSet results) throws SQLException
	{
		return new RankedMatch(results.getString("match_id"), UUID.fromString(results.getString("player_one_id")),
			UUID.fromString(results.getString("player_two_id")), Outcome.valueOf(results.getString("player_one_outcome")),
			Instant.ofEpochMilli(results.getLong("completed_at")), readRating(results, "p1_before_"),
			readRating(results, "p1_after_"), readRating(results, "p2_before_"), readRating(results, "p2_after_"));
	}

	private static Glicko2Rating readRating(ResultSet results, String prefix) throws SQLException
	{
		String rd = prefix.isEmpty() ? "rating_deviation" : prefix + "rd";
		String volatility = prefix.isEmpty() ? "volatility" : prefix + "vol";
		String games = prefix.isEmpty() ? "games_played" : prefix + "games";
		return new Glicko2Rating(results.getDouble(prefix + "rating"), results.getDouble(rd),
			results.getDouble(volatility), results.getLong(games), results.getLong(prefix + "wins"),
			results.getLong(prefix + "losses"), results.getLong(prefix + "draws"));
	}

	private void insertMatch(String matchId, UUID firstId, UUID secondId, Outcome outcome, long completedAt,
		Glicko2Rating firstBefore, Glicko2Rating firstAfter, Glicko2Rating secondBefore, Glicko2Rating secondAfter)
		throws SQLException
	{
		String placeholders = String.join(", ", java.util.Collections.nCopies(33, "?"));
		try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ranked_matches VALUES (" + placeholders + ")"))
		{
			statement.setString(1, matchId);
			statement.setString(2, firstId.toString());
			statement.setString(3, secondId.toString());
			statement.setString(4, outcome.name());
			statement.setLong(5, completedAt);
			setRating(statement, 6, firstBefore);
			setRating(statement, 13, firstAfter);
			setRating(statement, 20, secondBefore);
			setRating(statement, 27, secondAfter);
			statement.executeUpdate();
		}
	}

	private void updateRating(UUID accountId, Glicko2Rating rating) throws SQLException
	{
		try (PreparedStatement statement = connection.prepareStatement(
			"UPDATE ranked_accounts SET rating = ?, rating_deviation = ?, volatility = ?, games_played = ?, wins = ?, losses = ?, draws = ? WHERE account_id = ?"))
		{
			setRating(statement, 1, rating);
			statement.setString(8, accountId.toString());
			if (statement.executeUpdate() != 1) throw new SQLException("account disappeared during rating update");
		}
	}

	private static void setRating(PreparedStatement statement, int index, Glicko2Rating rating) throws SQLException
	{
		statement.setDouble(index, rating.rating());
		statement.setDouble(index + 1, rating.ratingDeviation());
		statement.setDouble(index + 2, rating.volatility());
		statement.setLong(index + 3, rating.gamesPlayed());
		statement.setLong(index + 4, rating.wins());
		statement.setLong(index + 5, rating.losses());
		statement.setLong(index + 6, rating.draws());
	}

	private <T> T inTransaction(SqlOperation<T> operation)
	{
		ensureOpen();
		try (Statement statement = connection.createStatement())
		{
			statement.execute("BEGIN IMMEDIATE");
			try
			{
				T result = operation.run();
				statement.execute("COMMIT");
				writeHealthy = true;
				return result;
			}
			catch (Throwable cause)
			{
				try { statement.execute("ROLLBACK"); } catch (SQLException rollback) { cause.addSuppressed(rollback); }
				if (cause instanceof SQLException || cause instanceof RankedPersistenceException) writeHealthy = false;
				if (cause instanceof RuntimeException runtime) throw runtime;
				if (cause instanceof Error error) throw error;
				throw failure("Ranked transaction failed", cause);
			}
		}
		catch (SQLException exception)
		{
			writeHealthy = false;
			throw failure("Ranked transaction failed", exception);
		}
	}

	private static byte[] validatePublicKey(byte[] encoded)
	{
		Objects.requireNonNull(encoded, "x509PublicKey");
		try
		{
			ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC")
				.generatePublic(new X509EncodedKeySpec(encoded));
			AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
			ECParameterSpec actual = key.getParams();
			if (!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
				|| !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor())
			{
				throw new IllegalArgumentException("public key must use P-256");
			}
			return key.getEncoded();
		}
		catch (GeneralSecurityException | ClassCastException exception)
		{
			throw new IllegalArgumentException("public key must be a P-256 X.509 public key", exception);
		}
	}

	private static void requireMatchingKey(RankedAccount account, byte[] key)
	{
		if (!MessageDigest.isEqual(account.publicKey(), key))
		{
			throw new SecurityException("OSRS display name is registered to a different public key");
		}
	}

	private static void validateMatch(String matchId, UUID first, UUID second, Outcome outcome)
	{
		if (matchId == null || matchId.isBlank() || matchId.length() > MAX_MATCH_ID_LENGTH)
			throw new IllegalArgumentException("matchId is invalid");
		Objects.requireNonNull(first, "playerOneId");
		Objects.requireNonNull(second, "playerTwoId");
		Objects.requireNonNull(outcome, "playerOneOutcome");
		if (first.equals(second)) throw new IllegalArgumentException("a player cannot play itself");
	}

	private static Outcome opposite(Outcome outcome)
	{
		return switch (outcome)
		{
			case WIN -> Outcome.LOSS;
			case LOSS -> Outcome.WIN;
			case DRAW -> Outcome.DRAW;
		};
	}

	private void ensureOpen()
	{
		if (closed) throw new IllegalStateException("ranked repository is closed");
	}

	private static RankedPersistenceException failure(String message, Throwable cause)
	{
		return new RankedPersistenceException(message, cause);
	}

	private record PendingMatch(UUID playerOneId, UUID playerTwoId, Outcome outcome, long completedAt)
	{
		private void requireSame(UUID first, UUID second, Outcome expectedOutcome)
		{
			if (!playerOneId.equals(first) || !playerTwoId.equals(second) || outcome != expectedOutcome)
				throw new IllegalArgumentException("matchId is already pending with different details");
		}
	}

	@FunctionalInterface
	private interface SqlOperation<T>
	{
		T run() throws Exception;
	}
}
