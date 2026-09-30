package com.osrstcgbattles.backend.ranked;

import com.osrstcgbattles.backend.rating.Glicko2Calculator;
import com.osrstcgbattles.backend.rating.Glicko2Rating;
import com.osrstcgbattles.backend.rating.Outcome;
import com.osrstcgbattles.backend.rating.RatingPeriodGame;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RankedRepositoryTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void initializesDurableSchemaAndRequiredSqliteConfiguration() throws Exception
	{
		Path database = databasePath();
		try (RankedRepository ignored = new RankedRepository(database);
			 Connection inspection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
			 Statement statement = inspection.createStatement())
		{
			try (ResultSet result = statement.executeQuery("PRAGMA journal_mode"))
			{
				assertTrue(result.next());
				assertEquals("wal", result.getString(1));
			}
			try (ResultSet result = statement.executeQuery(
				"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('ranked_accounts', 'ranked_matches')"))
			{
				assertTrue(result.next());
				assertEquals(2, result.getInt(1));
			}
		}

		assertTrue(database.toFile().isFile());
		try (RankedRepository reopened = new RankedRepository(database))
		{
			assertFalse(reopened.findAccount("Nobody").isPresent());
		}
	}

	@Test
	public void trustsFirstP256KeyAndUsesNormalizedUniqueIgn() throws Exception
	{
		byte[] firstKey = publicKey("secp256r1");
		byte[] otherKey = publicKey("secp256r1");
		try (RankedRepository repository = new RankedRepository(databasePath()))
		{
			RankedAccount created = repository.authenticateOrCreate("Rune_User", firstKey);
			RankedAccount authenticated = repository.authenticateOrCreate("rune user", firstKey);

			assertEquals(created.accountId(), authenticated.accountId());
			assertEquals("Rune_User", authenticated.displayName());
			assertEquals("rune user", authenticated.normalizedIgn());
			assertArrayEquals(firstKey, authenticated.publicKey());
			assertTrue(repository.authenticate("RUNE_USER", firstKey));
			assertFalse(repository.authenticate("RUNE_USER", otherKey));
			assertFalse(repository.authenticate("Unknown", firstKey));
			assertThrows(SecurityException.class,
				() -> repository.authenticateOrCreate("RUNE_USER", otherKey));
			assertEquals(1, countRows(databasePath(), "ranked_accounts"));

			byte[] exposed = authenticated.publicKey();
			exposed[0] ^= 1;
			assertArrayEquals(firstKey, authenticated.publicKey());
		}
	}

	@Test
	public void validatesDisplayNamesAndP256X509Keys() throws Exception
	{
		try (RankedRepository repository = new RankedRepository(databasePath()))
		{
			byte[] valid = publicKey("secp256r1");
			assertThrows(IllegalArgumentException.class, () -> repository.authenticateOrCreate("", valid));
			assertThrows(IllegalArgumentException.class, () -> repository.authenticateOrCreate(" thirteenchars", valid));
			assertThrows(IllegalArgumentException.class, () -> repository.authenticateOrCreate(" leading", valid));
			assertThrows(IllegalArgumentException.class, () -> repository.authenticateOrCreate("bad!name", valid));
			assertThrows(IllegalArgumentException.class,
				() -> repository.authenticateOrCreate("Valid", new byte[] {1, 2, 3}));
			assertThrows(IllegalArgumentException.class,
				() -> repository.authenticateOrCreate("Valid", publicKey("secp384r1")));
			assertEquals("abc 123-x", RankedRepository.normalizeIgn("AbC_123-X"));
		}
	}

	@Test
	public void recordsExactlyOnceFromBothPreMatchRatingsAndPersistsHistory() throws Exception
	{
		Path database = databasePath();
		UUID firstId;
		UUID secondId;
		RankedMatch recorded;
		try (RankedRepository repository = new RankedRepository(database))
		{
			firstId = repository.authenticateOrCreate("First", publicKey("secp256r1")).accountId();
			secondId = repository.authenticateOrCreate("Second", publicKey("secp256r1")).accountId();
			MatchRecording firstWrite = repository.recordCompletedMatch("match-1", firstId, secondId, Outcome.WIN);
			recorded = firstWrite.match();

			assertTrue(firstWrite.newlyRecorded());
			assertEquals(Glicko2Rating.unrated(), recorded.playerOneBefore());
			assertEquals(Glicko2Rating.unrated(), recorded.playerTwoBefore());
			Glicko2Calculator calculator = new Glicko2Calculator();
			assertRatingEquals(calculator.calculate(Glicko2Rating.unrated(),
				new RatingPeriodGame(Glicko2Rating.unrated(), Outcome.WIN)), recorded.playerOneAfter());
			assertRatingEquals(calculator.calculate(Glicko2Rating.unrated(),
				new RatingPeriodGame(Glicko2Rating.unrated(), Outcome.LOSS)), recorded.playerTwoAfter());

			MatchRecording duplicate = repository.recordCompletedMatch("match-1", firstId, secondId, Outcome.WIN);
			assertFalse(duplicate.newlyRecorded());
			assertEquals(recorded, duplicate.match());
			assertRatingEquals(recorded.playerOneAfter(), repository.getRating(firstId));
			assertEquals(1, repository.getMatchHistory(firstId, 10).size());
			assertThrows(IllegalArgumentException.class,
				() -> repository.recordCompletedMatch("match-1", firstId, secondId, Outcome.DRAW));
		}

		try (RankedRepository reopened = new RankedRepository(database))
		{
			assertRatingEquals(recorded.playerOneAfter(), reopened.getRating(firstId));
			List<RankedMatch> history = reopened.getMatchHistory(secondId, 10);
			assertEquals(1, history.size());
			assertEquals(recorded, history.get(0));
			assertFalse(reopened.recordCompletedMatch("match-1", firstId, secondId, Outcome.WIN).newlyRecorded());
		}
	}

	@Test
	public void recordsWinsLossesAndDrawsForBothPlayers() throws Exception
	{
		try (RankedRepository repository = new RankedRepository(databasePath()))
		{
			UUID first = repository.authenticateOrCreate("One", publicKey("secp256r1")).accountId();
			UUID second = repository.authenticateOrCreate("Two", publicKey("secp256r1")).accountId();
			repository.recordCompletedMatch("win", first, second, Outcome.WIN);
			repository.recordCompletedMatch("loss", first, second, Outcome.LOSS);
			repository.recordCompletedMatch("draw", first, second, Outcome.DRAW);

			Glicko2Rating firstRating = repository.getRating(first);
			Glicko2Rating secondRating = repository.getRating(second);
			assertEquals(3, firstRating.gamesPlayed());
			assertEquals(1, firstRating.wins());
			assertEquals(1, firstRating.losses());
			assertEquals(1, firstRating.draws());
			assertEquals(3, secondRating.gamesPlayed());
			assertEquals(1, secondRating.wins());
			assertEquals(1, secondRating.losses());
			assertEquals(1, secondRating.draws());
			assertEquals(2, repository.getMatchHistory(first, 2).size());
		}
	}

	@Test
	public void concurrentRepositoriesStillRecordAMatchOnce() throws Exception
	{
		Path database = databasePath();
		UUID first;
		UUID second;
		try (RankedRepository setup = new RankedRepository(database))
		{
			first = setup.authenticateOrCreate("One", publicKey("secp256r1")).accountId();
			second = setup.authenticateOrCreate("Two", publicKey("secp256r1")).accountId();
		}

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try (RankedRepository firstRepository = new RankedRepository(database);
			 RankedRepository secondRepository = new RankedRepository(database))
		{
			CountDownLatch start = new CountDownLatch(1);
			Future<MatchRecording> firstWrite = executor.submit(() ->
			{
				start.await();
				return firstRepository.recordCompletedMatch("simultaneous", first, second, Outcome.DRAW);
			});
			Future<MatchRecording> secondWrite = executor.submit(() ->
			{
				start.await();
				return secondRepository.recordCompletedMatch("simultaneous", first, second, Outcome.DRAW);
			});
			start.countDown();

			int newWrites = (firstWrite.get().newlyRecorded() ? 1 : 0) + (secondWrite.get().newlyRecorded() ? 1 : 0);
			assertEquals(1, newWrites);
			assertEquals(1, firstRepository.getRating(first).gamesPlayed());
			assertEquals(1, firstRepository.getMatchHistory(first, 10).size());
		}
		finally
		{
			executor.shutdownNow();
		}
	}

	@Test
	public void rollsBackHistoryAndBothRatingsWhenSecondUpdateFails() throws Exception
	{
		Path database = databasePath();
		UUID first;
		UUID second;
		try (RankedRepository repository = new RankedRepository(database))
		{
			first = repository.authenticateOrCreate("One", publicKey("secp256r1")).accountId();
			second = repository.authenticateOrCreate("Two", publicKey("secp256r1")).accountId();
		}
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
			 Statement statement = connection.createStatement())
		{
			statement.executeUpdate("CREATE TRIGGER reject_second_rating BEFORE UPDATE ON ranked_accounts "
				+ "WHEN OLD.account_id = '" + second + "' BEGIN SELECT RAISE(ABORT, 'forced failure'); END");
		}

		try (RankedRepository repository = new RankedRepository(database))
		{
			assertThrows(RankedPersistenceException.class,
				() -> repository.recordCompletedMatch("rollback", first, second, Outcome.WIN));
			assertEquals(Glicko2Rating.unrated(), repository.getRating(first));
			assertEquals(Glicko2Rating.unrated(), repository.getRating(second));
			assertTrue(repository.getMatchHistory(first, 10).isEmpty());
		}
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
			 Statement statement = connection.createStatement())
		{
			statement.executeUpdate("DROP TRIGGER reject_second_rating");
		}
		try (RankedRepository repository = new RankedRepository(database))
		{
			assertEquals(1, repository.getRating(first).gamesPlayed());
			assertEquals(1, repository.getRating(second).gamesPlayed());
			assertEquals(1, repository.getMatchHistory(first, 10).size());
		}
	}

	@Test
	public void rejectsInvalidMatchesAndClosesSafely() throws Exception
	{
		RankedRepository repository = new RankedRepository(databasePath());
		UUID first = repository.authenticateOrCreate("One", publicKey("secp256r1")).accountId();
		UUID missing = UUID.randomUUID();
		assertThrows(IllegalArgumentException.class,
			() -> repository.recordCompletedMatch("self", first, first, Outcome.DRAW));
		assertThrows(IllegalArgumentException.class,
			() -> repository.recordCompletedMatch("missing", first, missing, Outcome.WIN));
		assertNotEquals(first, missing);
		repository.close();
		repository.close();
		assertThrows(IllegalStateException.class, () -> repository.getRating(first));
	}

	private Path databasePath()
	{
		return temporaryFolder.getRoot().toPath().resolve("ranked.sqlite");
	}

	private static byte[] publicKey(String curve) throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec(curve));
		KeyPair pair = generator.generateKeyPair();
		return pair.getPublic().getEncoded();
	}

	private static int countRows(Path database, String table) throws Exception
	{
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
			 Statement statement = connection.createStatement();
			 ResultSet results = statement.executeQuery("SELECT count(*) FROM " + table))
		{
			assertTrue(results.next());
			return results.getInt(1);
		}
	}

	private static void assertRatingEquals(Glicko2Rating expected, Glicko2Rating actual)
	{
		assertEquals(expected.rating(), actual.rating(), 0.0000001);
		assertEquals(expected.ratingDeviation(), actual.ratingDeviation(), 0.0000001);
		assertEquals(expected.volatility(), actual.volatility(), 0.0000001);
		assertEquals(expected.gamesPlayed(), actual.gamesPlayed());
		assertEquals(expected.wins(), actual.wins());
		assertEquals(expected.losses(), actual.losses());
		assertEquals(expected.draws(), actual.draws());
	}
}
