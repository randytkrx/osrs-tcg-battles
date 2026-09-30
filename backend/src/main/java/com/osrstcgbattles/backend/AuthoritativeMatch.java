package com.osrstcgbattles.backend;

import com.google.gson.JsonObject;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MulliganCommand;
import com.osrstcgbattles.engine.PlayCardCommand;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.match.MatchView;
import com.osrstcgbattles.online.WireMatchView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Serializes all commands through the server-owned deterministic engine state. */
final class AuthoritativeMatch
{
	private static final int MAX_COMMAND_ID_LENGTH = 64;
	private static final int MAX_PROCESSED_COMMANDS = 256;

	private final String matchId = UUID.randomUUID().toString();
	private final String playerOneSession;
	private final String playerTwoSession;
	private final DuelscapeEngine engine = new DuelscapeEngine();
	private final Map<String, Submission> processed = new LinkedHashMap<String, Submission>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Submission> eldest)
		{
			return size() > MAX_PROCESSED_COMMANDS;
		}
	};
	private MatchState state;
	private long revision;
	private final long createdAtMillis;
	private long playerOneActionMillis;
	private long playerTwoActionMillis;
	private UUID playerOneAccount;
	private UUID playerTwoAccount;

	AuthoritativeMatch(String playerOneSession, Deck playerOneDeck, String playerTwoSession, Deck playerTwoDeck,
		CatalogDeckFactory deckFactory, long seed, long now)
	{
		this.playerOneSession = required(playerOneSession, "player one session");
		this.playerTwoSession = required(playerTwoSession, "player two session");
		if (this.playerOneSession.equals(this.playerTwoSession)) throw new IllegalArgumentException("players must differ");
		Objects.requireNonNull(deckFactory, "deckFactory");
		state = engine.newMatchWithMulligan(deckFactory.create(Objects.requireNonNull(playerOneDeck, "playerOneDeck")),
			deckFactory.create(Objects.requireNonNull(playerTwoDeck, "playerTwoDeck")), seed);
		createdAtMillis = now;
		playerOneActionMillis = now;
		playerTwoActionMillis = now;
	}

	synchronized Submission submit(String sessionId, String commandId, long expectedRevision, JsonObject payload, long now)
	{
		PlayerId seat = seat(sessionId);
		if (commandId == null || commandId.isEmpty() || commandId.length() > MAX_COMMAND_ID_LENGTH)
			return Submission.rejected(revision, "INVALID_COMMAND_ID");
		String idempotencyKey = sessionId + ':' + commandId;
		Submission previous = processed.get(idempotencyKey);
		if (previous != null) return previous;
		if (expectedRevision != revision) return Submission.rejected(revision, "REVISION_MISMATCH");

		Command command;
		try
		{
			command = parseCommand(Objects.requireNonNull(payload, "command"), seat);
		}
		catch (IllegalArgumentException | NullPointerException exception)
		{
			return Submission.rejected(revision, "INVALID_COMMAND");
		}
		CommandResult result = engine.execute(state, command);
		if (!result.isAccepted())
		{
			return Submission.rejected(revision,
				result.getRejectionReason().map(Enum::name).orElse("COMMAND_REJECTED"));
		}
		state = result.getState();
		revision++;
		setActionTime(seat, now);
		state.getActivePlayer().ifPresent(active -> setActionTime(active, now));
		Submission accepted = Submission.accepted(revision);
		processed.put(idempotencyKey, accepted);
		return accepted;
	}

	synchronized WireMatchView viewFor(String sessionId)
	{
		return WireMatchView.from(MatchView.forPlayer(state, seat(sessionId)));
	}

	synchronized boolean isComplete()
	{
		return state.getStatus() == com.osrstcgbattles.engine.MatchStatus.COMPLETE;
	}

	synchronized void setRankedAccounts(UUID playerOneAccount, UUID playerTwoAccount)
	{
		if (this.playerOneAccount != null || this.playerTwoAccount != null)
			throw new IllegalStateException("ranked accounts are already set");
		Objects.requireNonNull(playerOneAccount, "playerOneAccount");
		Objects.requireNonNull(playerTwoAccount, "playerTwoAccount");
		if (playerOneAccount.equals(playerTwoAccount)) throw new IllegalArgumentException("ranked players must differ");
		this.playerOneAccount = playerOneAccount;
		this.playerTwoAccount = playerTwoAccount;
	}

	synchronized boolean isRanked() { return playerOneAccount != null; }
	synchronized UUID getPlayerOneAccount() { return Objects.requireNonNull(playerOneAccount, "match is not ranked"); }
	synchronized UUID getPlayerTwoAccount() { return Objects.requireNonNull(playerTwoAccount, "match is not ranked"); }
	synchronized Optional<PlayerId> getWinner() { return state.getWinner(); }
	synchronized long getCreatedAtMillis() { return createdAtMillis; }

	synchronized Optional<String> timedOutSession(long now, long timeoutMillis)
	{
		long cutoff = now - timeoutMillis;
		if (state.getPhase() == MatchPhase.MULLIGAN)
		{
			boolean first = !state.getPlayer(PlayerId.PLAYER_ONE).isMulliganFinished()
				&& playerOneActionMillis <= cutoff;
			boolean second = !state.getPlayer(PlayerId.PLAYER_TWO).isMulliganFinished()
				&& playerTwoActionMillis <= cutoff;
			if (first == second) return Optional.empty();
			return Optional.of(first ? playerOneSession : playerTwoSession);
		}
		return state.getActivePlayer().filter(player -> actionTime(player) <= cutoff)
			.map(player -> player == PlayerId.PLAYER_ONE ? playerOneSession : playerTwoSession);
	}

	synchronized boolean bothMulligansTimedOut(long now, long timeoutMillis)
	{
		if (state.getPhase() != MatchPhase.MULLIGAN) return false;
		long cutoff = now - timeoutMillis;
		return !state.getPlayer(PlayerId.PLAYER_ONE).isMulliganFinished()
			&& !state.getPlayer(PlayerId.PLAYER_TWO).isMulliganFinished()
			&& playerOneActionMillis <= cutoff && playerTwoActionMillis <= cutoff;
	}

	synchronized boolean forceConcede(String sessionId, long now)
	{
		if (isComplete()) return false;
		PlayerId seat = seat(sessionId);
		CommandResult result = engine.execute(state, new ConcedeCommand(seat));
		if (!result.isAccepted()) return false;
		state = result.getState();
		revision++;
		setActionTime(seat, now);
		return true;
	}

	private long actionTime(PlayerId player)
	{
		return player == PlayerId.PLAYER_ONE ? playerOneActionMillis : playerTwoActionMillis;
	}

	private void setActionTime(PlayerId player, long now)
	{
		if (player == PlayerId.PLAYER_ONE) playerOneActionMillis = now;
		else playerTwoActionMillis = now;
	}

	synchronized long getRevision() { return revision; }
	String getMatchId() { return matchId; }
	String getPlayerOneSession() { return playerOneSession; }
	String getPlayerTwoSession() { return playerTwoSession; }

	private PlayerId seat(String sessionId)
	{
		if (playerOneSession.equals(sessionId)) return PlayerId.PLAYER_ONE;
		if (playerTwoSession.equals(sessionId)) return PlayerId.PLAYER_TWO;
		throw new IllegalArgumentException("session is not in this match");
	}

	private static Command parseCommand(JsonObject payload, PlayerId seat)
	{
		String type = text(payload, "type", 32);
		switch (type)
		{
			case "PLAY":
				String target = optionalText(payload, "target", 64);
				return target == null ? new PlayCardCommand(seat, text(payload, "cardId", 64))
					: new PlayCardCommand(seat, text(payload, "cardId", 64), target);
			case "ATTACK":
				String attackTarget = optionalText(payload, "target", 64);
				return attackTarget == null ? new AttackCommand(seat, text(payload, "attacker", 64))
					: new AttackCommand(seat, text(payload, "attacker", 64), attackTarget);
			case "END_TURN": return new EndTurnCommand(seat);
			case "CONCEDE": return new ConcedeCommand(seat);
			case "MULLIGAN": return new MulliganCommand(seat, text(payload, "cardId", 64));
			case "FINISH_MULLIGAN": return new FinishMulliganCommand(seat);
			default: throw new IllegalArgumentException("unknown command");
		}
	}

	private static String text(JsonObject value, String key, int maximum)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonPrimitive()
			|| !value.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid " + key);
		String text = value.get(key).getAsString();
		if (text.isEmpty() || text.length() > maximum) throw new IllegalArgumentException("invalid " + key);
		return text;
	}

	private static String optionalText(JsonObject value, String key, int maximum)
	{
		return value.has(key) ? text(value, key, maximum) : null;
	}

	private static String required(String value, String name)
	{
		if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required");
		return value;
	}

	static final class Submission
	{
		private final boolean accepted;
		private final long revision;
		private final String reason;

		private Submission(boolean accepted, long revision, String reason)
		{
			this.accepted = accepted;
			this.revision = revision;
			this.reason = reason;
		}

		static Submission accepted(long revision) { return new Submission(true, revision, null); }
		static Submission rejected(long revision, String reason) { return new Submission(false, revision, reason); }
		boolean isAccepted() { return accepted; }
		long getRevision() { return revision; }
		String getReason() { return reason; }
	}
}
