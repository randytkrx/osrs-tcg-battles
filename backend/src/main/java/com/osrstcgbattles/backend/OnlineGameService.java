package com.osrstcgbattles.backend;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.collection.OwnedCardCollectionStatus;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidationResult;
import com.osrstcgbattles.deck.DeckValidator;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.integration.CatalogCardLookup;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.online.OnlineEnvelope;
import com.osrstcgbattles.online.OnlineMessageType;
import com.osrstcgbattles.online.RankedAuthentication;
import com.osrstcgbattles.backend.ranked.MatchRecording;
import com.osrstcgbattles.backend.ranked.RankedAccount;
import com.osrstcgbattles.backend.ranked.RankedRepository;
import com.osrstcgbattles.backend.ranked.RankedPersistenceException;
import com.osrstcgbattles.backend.rating.Glicko2Rating;
import com.osrstcgbattles.backend.rating.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** In-memory private-lobby and authoritative-match lifecycle. Persistence is added before ranked play. */
final class OnlineGameService implements AutoCloseable
{
	private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
	private static final String DEFAULT_RANKED_AUDIENCE = "wss://play.deargod.live/v1/ws";
	private static final int LOBBY_CODE_LENGTH = 6;
	private static final int MAX_DISPLAY_NAME_LENGTH = 32;
	private static final int MAX_DECK_ID_LENGTH = 64;
	private static final int MAX_CARD_ID_LENGTH = 64;
	private static final long RECONNECT_GRACE_MILLIS = 2 * 60 * 1000L;
	private static final long AUTH_CHALLENGE_MILLIS = 60 * 1000L;
	private static final long HANDSHAKE_TIMEOUT_MILLIS = 30 * 1000L;
	private static final long IDLE_SESSION_MILLIS = 5 * 60 * 1000L;
	private static final long TURN_TIMEOUT_MILLIS = 3 * 60 * 1000L;
	private static final long MAX_MATCH_MILLIS = 60 * 60 * 1000L;
	private static final long MAX_WAITING_MILLIS = 15 * 60 * 1000L;
	private static final int MAX_SESSIONS = 512;
	private static final int MAX_SESSIONS_PER_SOURCE = 8;
	private static final double INITIAL_RANKED_RANGE = 200.0;
	private static final double RANKED_RANGE_PER_MINUTE = 50.0;
	private static final double MAX_RANKED_RANGE = 600.0;
	private static final long REQUEST_WINDOW_MILLIS = 10_000L;
	private static final int MAX_REQUESTS_PER_WINDOW = 50;

	private final Gson gson;
	private final BattleCardCatalog catalog;
	private final DeckValidator validator = new DeckValidator();
	private final CatalogCardLookup cardLookup;
	private final CatalogDeckFactory deckFactory;
	private final LongSupplier clock;
	private final RankedRepository rankedRepository;
	private final String rankedAudience;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Session> sessions = new HashMap<>();
	private final Map<String, Session> connections = new HashMap<>();
	private final Map<String, Session> reconnectTokens = new HashMap<>();
	private final Map<String, Lobby> lobbies = new HashMap<>();
	private final Map<String, AuthoritativeMatch> matches = new HashMap<>();
	private final Deque<Session> casualQueue = new ArrayDeque<>();
	private final Deque<Session> rankedQueue = new ArrayDeque<>();

	OnlineGameService(Gson gson)
	{
		this(gson, System::currentTimeMillis);
	}

	OnlineGameService(Gson gson, LongSupplier clock)
	{
		this(gson, clock, temporaryRankedRepository());
	}

	OnlineGameService(Gson gson, LongSupplier clock, RankedRepository rankedRepository)
	{
		this(gson, clock, rankedRepository, DEFAULT_RANKED_AUDIENCE);
	}

	OnlineGameService(Gson gson, LongSupplier clock, RankedRepository rankedRepository, String rankedAudience)
	{
		this.gson = gson;
		this.clock = clock;
		this.rankedRepository = rankedRepository;
		if (rankedAudience == null || !rankedAudience.startsWith("wss://") || rankedAudience.length() > 256)
			throw new IllegalArgumentException("ranked audience must be a wss:// URL");
		this.rankedAudience = rankedAudience;
		catalog = new BattleCardCatalogLoader(gson).loadDefault();
		cardLookup = new CatalogCardLookup(catalog);
		deckFactory = new CatalogDeckFactory(catalog);
	}

	synchronized String connect(Consumer<OnlineEnvelope> sender)
	{
		return connect(sender, () -> { });
	}

	synchronized String connect(Consumer<OnlineEnvelope> sender, Runnable closeConnection)
	{
		return connect(sender, closeConnection, "local");
	}

	synchronized String connect(Consumer<OnlineEnvelope> sender, Runnable closeConnection, String sourceAddress)
	{
		cleanupExpired();
		if (sessions.size() >= MAX_SESSIONS) throw new IllegalStateException("server connection limit reached");
		String source = sourceAddress == null ? "unknown" : sourceAddress.trim();
		long sourceSessions = sessions.values().stream().filter(session -> session.sourceAddress.equals(source)).count();
		if (sourceSessions >= MAX_SESSIONS_PER_SOURCE)
			throw new IllegalStateException("source connection limit reached");
		String connectionId = UUID.randomUUID().toString();
		long now = clock.getAsLong();
		Session session = new Session(UUID.randomUUID().toString(), connectionId,
			UUID.randomUUID().toString(), sender, closeConnection, source, now);
		connections.put(connectionId, session);
		sessions.put(session.id, session);
		reconnectTokens.put(session.reconnectToken, session);
		return connectionId;
	}

	synchronized void disconnect(String sessionId)
	{
		Session session = connections.remove(sessionId);
		if (session != null && sessionId.equals(session.connectionId))
		{
			session.sender = null;
			session.disconnectedAtMillis = clock.getAsLong();
			casualQueue.remove(session);
			rankedQueue.remove(session);
			session.queuedDeck = null;
			session.rankedDeck = null;
			session.rankedQueuedAtMillis = -1L;
			if (session.lobby == null) session.waitingSinceMillis = -1L;
		}
	}

	synchronized void handle(String connectionId, OnlineEnvelope envelope)
	{
		cleanupExpired();
		expireWaiting(clock.getAsLong());
		Session session = connections.get(connectionId);
		if (session == null) return;
		long now = clock.getAsLong();
		session.lastActivityMillis = now;
		if (now - session.requestWindowStartedMillis >= REQUEST_WINDOW_MILLIS)
		{
			session.requestWindowStartedMillis = now;
			session.requestsInWindow = 0;
		}
		if (++session.requestsInWindow > MAX_REQUESTS_PER_WINDOW)
		{
			sendError(session, envelope.getRequestId(), "RATE_LIMITED", "Too many requests");
			return;
		}
		try
		{
			switch (envelope.getType())
			{
				case HELLO: hello(session, envelope); break;
				case RESUME: resume(session, envelope); break;
				case AUTH_BEGIN: requireReady(session); beginAuthentication(session, envelope); break;
				case AUTH_RESPONSE: requireReady(session); completeAuthentication(session, envelope); break;
				case LOBBY_CREATE: requireReady(session); createLobby(session, envelope); break;
				case LOBBY_JOIN: requireReady(session); joinLobby(session, envelope); break;
				case LOBBY_LEAVE: requireReady(session); leaveLobby(session, envelope); break;
				case MATCH_COMMAND: requireReady(session); command(session, envelope); break;
				case MATCH_ACK: requireReady(session); acknowledgeMatch(session, envelope); break;
				case MATCH_ABANDON: requireReady(session); abandonMatch(session, envelope); break;
				case QUEUE_JOIN: requireReady(session); joinQueue(session, envelope); break;
				case QUEUE_LEAVE: requireReady(session); leaveQueue(session, envelope); break;
				case RANKED_QUEUE_JOIN: requireAuthenticated(session); joinRankedQueue(session, envelope); break;
				case RANKED_QUEUE_LEAVE: requireAuthenticated(session); leaveRankedQueue(session, envelope); break;
				default: sendError(session, envelope.getRequestId(), "UNSUPPORTED_MESSAGE", "Message is not accepted here");
			}
		}
		catch (IllegalArgumentException | SecurityException exception)
		{
			sendError(session, envelope.getRequestId(), "INVALID_REQUEST", exception.getMessage());
		}
		catch (RankedPersistenceException exception)
		{
			sendError(session, envelope.getRequestId(), "SERVICE_UNAVAILABLE", "Ranked storage is temporarily unavailable");
		}
	}

	BattleCardCatalog getCatalog() { return catalog; }

	@Override
	public synchronized void close()
	{
		rankedRepository.close();
	}

	private void hello(Session session, OnlineEnvelope envelope)
	{
		if (session.ready) throw new IllegalArgumentException("HELLO was already accepted");
		JsonObject payload = envelope.getPayload();
		String displayName = text(payload, "displayName", MAX_DISPLAY_NAME_LENGTH);
		RankedRepository.normalizeIgn(displayName);
		String catalogHash = text(payload, "catalogHash", 64);
		int ruleset = integer(payload, "rulesetVersion", 1, Integer.MAX_VALUE);
		if (!catalog.getSha256().equals(catalogHash) || ruleset != DuelscapeEngine.RULESET_VERSION)
			throw new IllegalArgumentException("catalog or ruleset is incompatible");
		session.displayName = displayName;
		session.ready = true;
		sendWelcome(session, envelope.getRequestId(), false);
	}

	private void resume(Session provisional, OnlineEnvelope envelope)
	{
		if (provisional.ready || provisional.lobby != null || provisional.match != null
			|| provisional.queuedDeck != null || provisional.rankedDeck != null)
			throw new IllegalArgumentException("RESUME requires a fresh connection");
		String token = text(envelope.getPayload(), "reconnectToken", 64);
		Session resumed = reconnectTokens.get(token);
		long now = clock.getAsLong();
		if (resumed == null || resumed == provisional || resumed.sender != null
			|| resumed.disconnectedAtMillis < 0 || now - resumed.disconnectedAtMillis > RECONNECT_GRACE_MILLIS)
			throw new IllegalArgumentException("reconnect token is invalid");
		Consumer<OnlineEnvelope> sender = provisional.sender;
		Runnable closeConnection = provisional.closeConnection;
		connections.remove(provisional.connectionId);
		sessions.remove(provisional.id);
		reconnectTokens.remove(provisional.reconnectToken);
		connections.remove(resumed.connectionId);
		resumed.connectionId = provisional.connectionId;
		resumed.sender = sender;
		resumed.closeConnection = closeConnection;
		resumed.disconnectedAtMillis = -1L;
		resumed.lastActivityMillis = now;
		connections.put(resumed.connectionId, resumed);
		sendWelcome(resumed, envelope.getRequestId(), true);
		if (!resumed.matchFoundAcknowledged && resumed.matchFoundMessage != null)
			sendDirect(resumed, resumed.matchFoundMessage);
		for (OnlineEnvelope pending : resumed.pendingMessages) sendDirect(resumed, pending);
		if (resumed.match != null)
		{
			sendState(resumed, eventId());
		}
	}

	private void beginAuthentication(Session session, OnlineEnvelope envelope)
	{
		if (session.rankedAccount != null) throw new IllegalArgumentException("session is already authenticated");
		JsonObject payload = envelope.getPayload();
		String ign = text(payload, "ign", 12);
		if (!RankedRepository.normalizeIgn(ign).equals(RankedRepository.normalizeIgn(session.displayName)))
			throw new IllegalArgumentException("ranked IGN must match the connected player name");
		byte[] publicKey = decode(payload, "publicKey", 256);
		try
		{
			KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey));
		}
		catch (GeneralSecurityException exception)
		{
			throw new IllegalArgumentException("public key is invalid", exception);
		}
		byte[] nonce = new byte[32];
		random.nextBytes(nonce);
		String challengeId = UUID.randomUUID().toString();
		session.authChallenge = new AuthenticationChallenge(challengeId, ign, publicKey, nonce,
			clock.getAsLong() + AUTH_CHALLENGE_MILLIS);
		JsonObject response = new JsonObject();
		response.addProperty("challengeId", challengeId);
		response.addProperty("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
		send(session, new OnlineEnvelope(envelope.getRequestId(), OnlineMessageType.AUTH_CHALLENGE, response));
	}

	private void completeAuthentication(Session session, OnlineEnvelope envelope)
	{
		AuthenticationChallenge challenge = session.authChallenge;
		session.authChallenge = null;
		if (challenge == null || !challenge.id.equals(text(envelope.getPayload(), "challengeId", 64))
			|| clock.getAsLong() > challenge.expiresAtMillis)
			throw new IllegalArgumentException("authentication challenge is invalid or expired");
		byte[] signature = decode(envelope.getPayload(), "signature", 256);
		try
		{
			Signature verifier = Signature.getInstance("SHA256withECDSA");
			verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(challenge.publicKey)));
			verifier.update(RankedAuthentication.challenge(rankedAudience, challenge.id, challenge.nonce,
				challenge.ign, challenge.publicKey));
			if (!verifier.verify(signature)) throw new SecurityException("device signature is invalid");
		}
		catch (GeneralSecurityException exception)
		{
			throw new SecurityException("device signature is invalid", exception);
		}
		session.rankedAccount = rankedRepository.authenticateOrCreate(challenge.ign, challenge.publicKey);
		sendAuthenticated(session, envelope.getRequestId());
	}

	private void createLobby(Session session, OnlineEnvelope envelope)
	{
		requireIdle(session);
		Deck deck = parseDeck(object(envelope.getPayload(), "deck"));
		String code;
		do { code = lobbyCode(); } while (lobbies.containsKey(code));
		Lobby lobby = new Lobby(code, session, deck);
		lobbies.put(code, lobby);
		session.lobby = lobby;
		session.waitingSinceMillis = clock.getAsLong();
		JsonObject payload = new JsonObject();
		payload.addProperty("code", code);
		send(session, new OnlineEnvelope(envelope.getRequestId(), OnlineMessageType.LOBBY_CREATED, payload));
	}

	private void joinLobby(Session session, OnlineEnvelope envelope)
	{
		requireIdle(session);
		String code = text(envelope.getPayload(), "code", LOBBY_CODE_LENGTH).toUpperCase(Locale.ROOT);
		Lobby lobby = lobbies.get(code);
		if (lobby == null) throw new IllegalArgumentException("lobby was not found");
		if (lobby.owner.sender == null)
		{
			lobbies.remove(code);
			lobby.owner.lobby = null;
			throw new IllegalArgumentException("lobby owner is reconnecting");
		}
		if (lobby.owner == session) throw new IllegalArgumentException("cannot join your own lobby");
		Deck deck = parseDeck(object(envelope.getPayload(), "deck"));
		lobby.owner.lobby = null;
		lobby.owner.waitingSinceMillis = -1L;
		lobbies.remove(code);

		JsonObject joined = new JsonObject();
		joined.addProperty("code", code);
		send(session, new OnlineEnvelope(envelope.getRequestId(), OnlineMessageType.LOBBY_JOINED, joined));
		startMatch(lobby.owner, lobby.ownerDeck, session, deck, false);
	}

	private void joinQueue(Session session, OnlineEnvelope envelope)
	{
		requireIdle(session);
		Deck deck = parseDeck(object(envelope.getPayload(), "deck"));
		Session opponent = null;
		while (!casualQueue.isEmpty() && opponent == null)
		{
			Session candidate = casualQueue.removeFirst();
			if (candidate != session && candidate.sender != null && candidate.match == null
				&& candidate.queuedDeck != null) opponent = candidate;
		}
		if (opponent == null)
		{
			session.queuedDeck = deck;
			session.waitingSinceMillis = clock.getAsLong();
			casualQueue.addLast(session);
			sendQueueStatus(session, envelope.getRequestId(), "WAITING");
			return;
		}
		Deck opponentDeck = opponent.queuedDeck;
		opponent.queuedDeck = null;
		startMatch(opponent, opponentDeck, session, deck, false);
	}

	private void joinRankedQueue(Session session, OnlineEnvelope envelope)
	{
		requireIdle(session);
		session.rankedAccount = rankedRepository.findAccount(session.displayName)
			.orElseThrow(() -> new IllegalArgumentException("ranked account was not found"));
		if (rankedRepository.hasPendingMatch(session.rankedAccount.accountId()))
			throw new IllegalArgumentException("a previous ranked result is still processing");
		for (Session other : sessions.values())
		{
			if (other != session && other.rankedAccount != null
				&& other.rankedAccount.accountId().equals(session.rankedAccount.accountId())
				&& (other.rankedDeck != null || other.match != null && other.match.isRanked()))
				throw new IllegalArgumentException("ranked account is already queued or playing");
		}
		Deck deck = parseDeck(object(envelope.getPayload(), "deck"));
		Session opponent = null;
		double closestDifference = Double.MAX_VALUE;
		long now = clock.getAsLong();
		for (java.util.Iterator<Session> iterator = rankedQueue.iterator(); iterator.hasNext(); )
		{
			Session candidate = iterator.next();
			if (candidate.sender == null || candidate.match != null || candidate.rankedDeck == null
				|| candidate.rankedAccount == null)
			{
				iterator.remove();
				continue;
			}
			if (candidate == session || candidate.rankedAccount.accountId().equals(session.rankedAccount.accountId()))
				continue;
			double difference = Math.abs(candidate.rankedAccount.rating().rating()
				- session.rankedAccount.rating().rating());
			double range = Math.max(rankedRange(now - candidate.rankedQueuedAtMillis), INITIAL_RANKED_RANGE);
			if (difference <= range && difference < closestDifference)
			{
				opponent = candidate;
				closestDifference = difference;
			}
		}
		if (opponent == null)
		{
			session.rankedDeck = deck;
			session.rankedQueuedAtMillis = now;
			session.waitingSinceMillis = now;
			rankedQueue.addLast(session);
			sendRankedQueueStatus(session, envelope.getRequestId(), "WAITING");
			return;
		}
		Deck opponentDeck = opponent.rankedDeck;
		rankedQueue.remove(opponent);
		opponent.rankedDeck = null;
		opponent.rankedQueuedAtMillis = -1L;
		opponent.waitingSinceMillis = -1L;
		startMatch(opponent, opponentDeck, session, deck, true);
	}

	private void leaveRankedQueue(Session session, OnlineEnvelope envelope)
	{
		if (session.rankedDeck == null) throw new IllegalArgumentException("session is not ranked queued");
		rankedQueue.remove(session);
		session.rankedDeck = null;
		session.rankedQueuedAtMillis = -1L;
		session.waitingSinceMillis = -1L;
		sendRankedQueueStatus(session, envelope.getRequestId(), "LEFT");
	}

	private static double rankedRange(long waitedMillis)
	{
		double expanded = INITIAL_RANKED_RANGE + Math.max(0L, waitedMillis) / 60_000.0 * RANKED_RANGE_PER_MINUTE;
		return Math.min(MAX_RANKED_RANGE, expanded);
	}

	private void leaveQueue(Session session, OnlineEnvelope envelope)
	{
		if (session.queuedDeck != null)
		{
			casualQueue.remove(session);
			session.queuedDeck = null;
			session.waitingSinceMillis = -1L;
			sendQueueStatus(session, envelope.getRequestId(), "LEFT");
		}
		else if (session.rankedDeck != null)
		{
			rankedQueue.remove(session);
			session.rankedDeck = null;
			session.rankedQueuedAtMillis = -1L;
			session.waitingSinceMillis = -1L;
			sendRankedQueueStatus(session, envelope.getRequestId(), "LEFT");
		}
		else throw new IllegalArgumentException("session is not queued");
	}

	private void startMatch(Session first, Deck firstDeck, Session second, Deck secondDeck, boolean ranked)
	{
		AuthoritativeMatch match = new AuthoritativeMatch(first.id, firstDeck, second.id, secondDeck,
			deckFactory, random.nextLong(), clock.getAsLong());
		if (ranked)
			match.setRankedAccounts(first.rankedAccount.accountId(), second.rankedAccount.accountId());
		matches.put(match.getMatchId(), match);
		first.match = match;
		second.match = match;
		first.waitingSinceMillis = -1L;
		second.waitingSinceMillis = -1L;
		first.matchFoundAcknowledged = false;
		second.matchFoundAcknowledged = false;
		sendMatchFound(first, second, match, PlayerIdName.ONE);
		sendMatchFound(second, first, match, PlayerIdName.TWO);
		sendState(first, eventId());
		sendState(second, eventId());
	}

	private void sendRankedQueueStatus(Session session, String requestId, String status)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("status", status);
		addRating(payload, session.rankedAccount.rating());
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.RANKED_QUEUE_STATUS, payload));
	}

	private void sendQueueStatus(Session session, String requestId, String status)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("status", status);
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.QUEUE_STATUS, payload));
	}

	private void leaveLobby(Session session, OnlineEnvelope envelope)
	{
		if (session.lobby == null) throw new IllegalArgumentException("session is not in a lobby");
		lobbies.remove(session.lobby.code);
		session.lobby = null;
		session.waitingSinceMillis = -1L;
		JsonObject payload = new JsonObject();
		payload.addProperty("left", true);
		send(session, new OnlineEnvelope(envelope.getRequestId(), OnlineMessageType.LOBBY_LEAVE, payload));
	}

	private void command(Session session, OnlineEnvelope envelope)
	{
		if (session.match == null) throw new IllegalArgumentException("session is not in a match");
		JsonObject payload = envelope.getPayload();
		String matchId = text(payload, "matchId", 64);
		if (!session.match.getMatchId().equals(matchId)) throw new IllegalArgumentException("match ID is invalid");
		String commandId = text(payload, "commandId", 64);
		long revision = longInteger(payload, "expectedRevision", 0, Long.MAX_VALUE);
		AuthoritativeMatch.Submission result = session.match.submit(session.id, commandId, revision,
			object(payload, "command"), clock.getAsLong());
		if (!result.isAccepted())
		{
			JsonObject rejected = new JsonObject();
			rejected.addProperty("matchId", matchId);
			rejected.addProperty("revision", result.getRevision());
			rejected.addProperty("reason", result.getReason());
			send(session, new OnlineEnvelope(envelope.getRequestId(), OnlineMessageType.MATCH_REJECTED, rejected));
			sendState(session, eventId());
			return;
		}
		Session first = sessions.get(session.match.getPlayerOneSession());
		Session second = sessions.get(session.match.getPlayerTwoSession());
		if (session.match.isComplete())
		{
			finishCompletedMatch(session.match, first, second);
		}
		else
		{
			sendState(first, envelope.getRequestId());
			sendState(second, eventId());
		}
	}

	private void acknowledgeMatch(Session session, OnlineEnvelope envelope)
	{
		String matchId = text(envelope.getPayload(), "matchId", 64);
		boolean terminal = bool(envelope.getPayload(), "terminal");
		if (!terminal)
		{
			if (session.match == null || !session.match.getMatchId().equals(matchId))
				throw new IllegalArgumentException("match ID is invalid");
			session.matchFoundAcknowledged = true;
			return;
		}
		boolean owned = session.pendingMessages.stream()
			.anyMatch(message -> matchId.equals(optionalText(message.getPayload(), "matchId", 64)));
		if (!owned) throw new IllegalArgumentException("match ID is invalid");
		session.pendingMessages.removeIf(message -> matchId.equals(optionalText(message.getPayload(), "matchId", 64)));
		if (session.matchFoundMessage != null
			&& matchId.equals(optionalText(session.matchFoundMessage.getPayload(), "matchId", 64)))
			session.matchFoundMessage = null;
	}

	private void abandonMatch(Session session, OnlineEnvelope envelope)
	{
		String matchId = text(envelope.getPayload(), "matchId", 64);
		if (session.match == null)
		{
			boolean terminalPending = session.pendingMessages.stream()
				.anyMatch(message -> matchId.equals(optionalText(message.getPayload(), "matchId", 64)));
			if (terminalPending) return;
			throw new IllegalArgumentException("session is not in a match");
		}
		if (!session.match.getMatchId().equals(matchId)) throw new IllegalArgumentException("match ID is invalid");
		AuthoritativeMatch match = session.match;
		if (!match.forceConcede(session.id, clock.getAsLong()))
			throw new IllegalArgumentException("match cannot be abandoned");
		finishCompletedMatch(match, sessions.get(match.getPlayerOneSession()),
			sessions.get(match.getPlayerTwoSession()));
	}

	private void finishCompletedMatch(AuthoritativeMatch completed, Session first, Session second)
	{
		MatchRecording recording = null;
		if (completed.isRanked())
		{
			try { recording = recordRankedResult(completed, first, second); }
			catch (RuntimeException exception)
			{
				sendError(first, eventId(), "RATING_PERSISTENCE_FAILED", "Match result storage is retrying");
				sendError(second, eventId(), "RATING_PERSISTENCE_FAILED", "Match result storage is retrying");
				System.err.println("Could not persist ranked match " + completed.getMatchId() + ": "
					+ exception.getMessage());
				return;
			}
		}
		if (recording != null)
		{
			sendRating(first, completed.getMatchId(), recording.match().playerOneAfter());
			sendRating(second, completed.getMatchId(), recording.match().playerTwoAfter());
		}
		sendComplete(first);
		sendComplete(second);
		matches.remove(completed.getMatchId());
		if (first != null && first.match == completed) first.match = null;
		if (second != null && second.match == completed) second.match = null;
	}

	private MatchRecording recordRankedResult(AuthoritativeMatch match, Session first, Session second)
	{
		Outcome outcome = match.getWinner().map(winner -> winner == PlayerId.PLAYER_ONE ? Outcome.WIN : Outcome.LOSS)
			.orElse(Outcome.DRAW);
		MatchRecording recording = rankedRepository.recordCompletedMatch(match.getMatchId(),
			match.getPlayerOneAccount(), match.getPlayerTwoAccount(), outcome);
		if (first != null) first.rankedAccount = withRating(first.rankedAccount, recording.match().playerOneAfter());
		if (second != null) second.rankedAccount = withRating(second.rankedAccount, recording.match().playerTwoAfter());
		return recording;
	}

	private static RankedAccount withRating(RankedAccount account, Glicko2Rating rating)
	{
		return new RankedAccount(account.accountId(), account.displayName(), account.normalizedIgn(),
			account.publicKey(), rating);
	}

	private void cleanupExpired()
	{
		long now = clock.getAsLong();
		long cutoff = now - RECONNECT_GRACE_MILLIS;
		List<Session> expired = new ArrayList<>();
		for (Session session : sessions.values())
		{
			if (session.sender == null && session.disconnectedAtMillis >= 0
				&& session.disconnectedAtMillis <= cutoff) expired.add(session);
			else if (session.sender != null && !session.ready
				&& session.connectedAtMillis <= now - HANDSHAKE_TIMEOUT_MILLIS) expired.add(session);
			else if (session.sender != null && session.ready && session.lobby == null && session.match == null
				&& session.queuedDeck == null && session.rankedDeck == null
				&& session.lastActivityMillis <= now - IDLE_SESSION_MILLIS) expired.add(session);
		}
		for (Session session : expired) removeExpired(session);
	}

	private void expireWaiting(long now)
	{
		for (Session session : new ArrayList<>(sessions.values()))
		{
			if (session.sender == null || session.waitingSinceMillis < 0
				|| session.waitingSinceMillis > now - MAX_WAITING_MILLIS) continue;
			if (session.lobby != null)
			{
				lobbies.remove(session.lobby.code);
				session.lobby = null;
				JsonObject payload = new JsonObject();
				payload.addProperty("left", true);
				payload.addProperty("expired", true);
				send(session, new OnlineEnvelope(eventId(), OnlineMessageType.LOBBY_LEAVE, payload));
			}
			else if (session.queuedDeck != null)
			{
				casualQueue.remove(session);
				session.queuedDeck = null;
				sendQueueStatus(session, eventId(), "EXPIRED");
			}
			else if (session.rankedDeck != null)
			{
				rankedQueue.remove(session);
				session.rankedDeck = null;
				session.rankedQueuedAtMillis = -1L;
				sendRankedQueueStatus(session, eventId(), "EXPIRED");
			}
			session.waitingSinceMillis = -1L;
		}
	}

	synchronized void maintenance()
	{
		cleanupExpired();
		long now = clock.getAsLong();
		expireWaiting(now);
		for (AuthoritativeMatch match : new ArrayList<>(matches.values()))
		{
			if (match.isComplete())
			{
				finishCompletedMatch(match, sessions.get(match.getPlayerOneSession()),
					sessions.get(match.getPlayerTwoSession()));
				continue;
			}
			java.util.Optional<String> timedOut = match.timedOutSession(now, TURN_TIMEOUT_MILLIS);
			if (timedOut.isPresent() && match.forceConcede(timedOut.get(), now))
			{
				finishCompletedMatch(match, sessions.get(match.getPlayerOneSession()),
					sessions.get(match.getPlayerTwoSession()));
			}
			else if (match.bothMulligansTimedOut(now, TURN_TIMEOUT_MILLIS))
			{
				abortMatch(match, "Both mulligan timers expired");
			}
			else if (match.getCreatedAtMillis() <= now - MAX_MATCH_MILLIS)
			{
				abortMatch(match, "Match duration limit reached");
			}
		}
	}

	synchronized boolean isHealthy()
	{
		return rankedRepository.isHealthy();
	}

	private void removeExpired(Session session)
	{
		if (session.lobby != null)
		{
			lobbies.remove(session.lobby.code);
			session.lobby = null;
		}
		casualQueue.remove(session);
		rankedQueue.remove(session);
		session.queuedDeck = null;
		session.rankedDeck = null;
		session.rankedQueuedAtMillis = -1L;
		if (session.match != null)
		{
			AuthoritativeMatch abandoned = session.match;
			if (abandoned.isComplete())
				finishCompletedMatch(abandoned, sessions.get(abandoned.getPlayerOneSession()),
					sessions.get(abandoned.getPlayerTwoSession()));
			else if (abandoned.isRanked() && abandoned.forceConcede(session.id, clock.getAsLong()))
				finishCompletedMatch(abandoned, sessions.get(abandoned.getPlayerOneSession()),
					sessions.get(abandoned.getPlayerTwoSession()));
			else abortMatch(abandoned, "Opponent did not reconnect");
		}
		sessions.remove(session.id);
		reconnectTokens.remove(session.reconnectToken);
		connections.remove(session.connectionId);
		session.closeConnection.run();
	}

	private void abortMatch(AuthoritativeMatch match, String reason)
	{
		matches.remove(match.getMatchId());
		Session first = sessions.get(match.getPlayerOneSession());
		Session second = sessions.get(match.getPlayerTwoSession());
		sendAborted(first, match, reason);
		sendAborted(second, match, reason);
		if (first != null && first.match == match) first.match = null;
		if (second != null && second.match == match) second.match = null;
	}

	private void sendAborted(Session session, AuthoritativeMatch match, String reason)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", match.getMatchId());
		payload.addProperty("reason", reason);
		send(session, new OnlineEnvelope(eventId(), OnlineMessageType.MATCH_ABORTED, payload));
	}

	private void sendWelcome(Session session, String requestId, boolean resumed)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("sessionId", session.id);
		payload.addProperty("reconnectToken", session.reconnectToken);
		payload.addProperty("catalogHash", catalog.getSha256());
		payload.addProperty("rulesetVersion", catalog.getRulesetVersion());
		payload.addProperty("resumed", resumed);
		payload.addProperty("authenticated", session.rankedAccount != null);
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.WELCOME, payload));
	}

	private void sendAuthenticated(Session session, String requestId)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("accountId", session.rankedAccount.accountId().toString());
		payload.addProperty("ign", session.rankedAccount.displayName());
		addRating(payload, session.rankedAccount.rating());
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.AUTHENTICATED, payload));
	}

	private void sendRating(Session session, String matchId, Glicko2Rating rating)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", matchId);
		addRating(payload, rating);
		send(session, new OnlineEnvelope(eventId(), OnlineMessageType.RATING_UPDATE, payload));
	}

	private static void addRating(JsonObject payload, Glicko2Rating rating)
	{
		payload.addProperty("rating", Math.round(rating.rating()));
		payload.addProperty("ratingDeviation", Math.round(rating.ratingDeviation()));
		payload.addProperty("gamesPlayed", rating.gamesPlayed());
		payload.addProperty("wins", rating.wins());
		payload.addProperty("losses", rating.losses());
		payload.addProperty("draws", rating.draws());
	}

	private void sendMatchFound(Session player, Session opponent, AuthoritativeMatch match, PlayerIdName seat)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", match.getMatchId());
		payload.addProperty("seat", seat.wireName);
		payload.addProperty("opponentDisplayName", opponent.displayName);
		payload.addProperty("revision", match.getRevision());
		payload.addProperty("ranked", match.isRanked());
		OnlineEnvelope message = new OnlineEnvelope(eventId(), OnlineMessageType.MATCH_FOUND, payload);
		player.matchFoundMessage = message;
		send(player, message);
	}

	private void sendState(Session session, String requestId)
	{
		if (session == null || session.match == null) return;
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", session.match.getMatchId());
		payload.addProperty("revision", session.match.getRevision());
		payload.add("view", gson.toJsonTree(session.match.viewFor(session.id)));
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.MATCH_STATE, payload));
	}

	private void sendComplete(Session session)
	{
		if (session == null || session.match == null) return;
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", session.match.getMatchId());
		payload.addProperty("revision", session.match.getRevision());
		payload.add("view", gson.toJsonTree(session.match.viewFor(session.id)));
		send(session, new OnlineEnvelope(eventId(), OnlineMessageType.MATCH_COMPLETE, payload));
	}

	private Deck parseDeck(JsonObject value)
	{
		String deckId = text(value, "deckId", MAX_DECK_ID_LENGTH);
		JsonArray cards = array(value, "cards");
		if (cards.size() < 1 || cards.size() > DeckValidator.MAX_CARDS)
			throw new IllegalArgumentException("deck entry count is invalid");
		List<DeckEntry> entries = new ArrayList<>();
		for (JsonElement element : cards)
		{
			if (!element.isJsonObject()) throw new IllegalArgumentException("deck card must be an object");
			JsonObject card = element.getAsJsonObject();
			entries.add(new DeckEntry(text(card, "cardId", MAX_CARD_ID_LENGTH),
				integer(card, "count", 1, DeckValidator.MAX_CARDS)));
		}
		Deck deck = new Deck(deckId, "Online deck", entries);
		DeckValidationResult result = validator.validate(deck, cardLookup, Collections.emptySet(),
			OwnedCardCollectionStatus.UNKNOWN);
		if (!result.isValid()) throw new IllegalArgumentException("deck is invalid");
		return deck;
	}

	private static void requireReady(Session session)
	{
		if (!session.ready) throw new IllegalArgumentException("HELLO is required");
	}

	private static void requireIdle(Session session)
	{
		if (session.lobby != null || session.match != null || session.queuedDeck != null || session.rankedDeck != null)
			throw new IllegalArgumentException("session is busy");
		if (!session.pendingMessages.isEmpty())
			throw new IllegalArgumentException("previous match result must be acknowledged first");
	}

	private static void requireAuthenticated(Session session)
	{
		requireReady(session);
		if (session.rankedAccount == null) throw new IllegalArgumentException("ranked authentication is required");
	}

	private void sendError(Session session, String requestId, String code, String message)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("code", code);
		payload.addProperty("message", message == null ? "Request failed" : message);
		send(session, new OnlineEnvelope(requestId, OnlineMessageType.ERROR, payload));
	}

	private static void send(Session session, OnlineEnvelope message)
	{
		if (session == null) return;
		if (message.getType() == OnlineMessageType.MATCH_COMPLETE
			|| message.getType() == OnlineMessageType.MATCH_ABORTED
			|| message.getType() == OnlineMessageType.RATING_UPDATE)
		{
			if (session.pendingMessages.size() >= 4) session.pendingMessages.removeFirst();
			session.pendingMessages.addLast(message);
		}
		sendDirect(session, message);
	}

	private static void sendDirect(Session session, OnlineEnvelope message)
	{
		if (session != null && session.sender != null) session.sender.accept(message);
	}

	private String lobbyCode()
	{
		StringBuilder code = new StringBuilder(LOBBY_CODE_LENGTH);
		for (int i = 0; i < LOBBY_CODE_LENGTH; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		return code.toString();
	}

	private static String eventId() { return UUID.randomUUID().toString(); }

	private static String text(JsonObject value, String key, int maximum)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonPrimitive()
			|| !value.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " is required");
		String text = value.get(key).getAsString().trim();
		if (text.isEmpty() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl))
			throw new IllegalArgumentException(key + " is invalid");
		return text;
	}

	private static String optionalText(JsonObject value, String key, int maximum)
	{
		try { return text(value, key, maximum); }
		catch (IllegalArgumentException exception) { return null; }
	}

	private static boolean bool(JsonObject value, String key)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonPrimitive()
			|| !value.get(key).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " is required");
		return value.get(key).getAsBoolean();
	}

	private static int integer(JsonObject value, String key, int minimum, int maximum)
	{
		long number = longInteger(value, key, minimum, maximum);
		return (int) number;
	}

	private static long longInteger(JsonObject value, String key, long minimum, long maximum)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonPrimitive()
			|| !value.get(key).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " is required");
		try
		{
			long number = value.get(key).getAsBigDecimal().longValueExact();
			if (number < minimum || number > maximum) throw new IllegalArgumentException(key + " is invalid");
			return number;
		}
		catch (ArithmeticException | NumberFormatException exception)
		{
			throw new IllegalArgumentException(key + " is invalid", exception);
		}
	}

	private static JsonObject object(JsonObject value, String key)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonObject())
			throw new IllegalArgumentException(key + " is required");
		return value.getAsJsonObject(key);
	}

	private static JsonArray array(JsonObject value, String key)
	{
		if (value == null || !value.has(key) || !value.get(key).isJsonArray())
			throw new IllegalArgumentException(key + " is required");
		return value.getAsJsonArray(key);
	}

	private static byte[] decode(JsonObject value, String key, int maximumBytes)
	{
		String encoded = text(value, key, maximumBytes * 2);
		try
		{
			byte[] decoded = Base64.getUrlDecoder().decode(encoded);
			if (decoded.length == 0 || decoded.length > maximumBytes)
				throw new IllegalArgumentException(key + " is invalid");
			return decoded;
		}
		catch (IllegalArgumentException exception)
		{
			throw new IllegalArgumentException(key + " is invalid", exception);
		}
	}

	private static RankedRepository temporaryRankedRepository()
	{
		try
		{
			Path database = Files.createTempFile("duelscape-ranked-test-", ".db");
			database.toFile().deleteOnExit();
			return new RankedRepository(database);
		}
		catch (java.io.IOException exception)
		{
			throw new IllegalStateException("Could not create temporary ranked database", exception);
		}
	}

	private enum PlayerIdName
	{
		ONE("PLAYER_ONE"), TWO("PLAYER_TWO");
		private final String wireName;
		PlayerIdName(String wireName) { this.wireName = wireName; }
	}

	private static final class Session
	{
		private final String id;
		private final String reconnectToken;
		private Runnable closeConnection;
		private final String sourceAddress;
		private final long connectedAtMillis;
		private String connectionId;
		private Consumer<OnlineEnvelope> sender;
		private String displayName;
		private boolean ready;
		private Lobby lobby;
		private AuthoritativeMatch match;
		private Deck queuedDeck;
		private Deck rankedDeck;
		private RankedAccount rankedAccount;
		private AuthenticationChallenge authChallenge;
		private final Deque<OnlineEnvelope> pendingMessages = new ArrayDeque<>();
		private OnlineEnvelope matchFoundMessage;
		private boolean matchFoundAcknowledged;
		private long disconnectedAtMillis = -1L;
		private long rankedQueuedAtMillis = -1L;
		private long waitingSinceMillis = -1L;
		private long lastActivityMillis;
		private long requestWindowStartedMillis;
		private int requestsInWindow;

		private Session(String id, String connectionId, String reconnectToken, Consumer<OnlineEnvelope> sender,
			Runnable closeConnection, String sourceAddress, long now)
		{
			this.id = id;
			this.connectionId = connectionId;
			this.reconnectToken = reconnectToken;
			this.sender = sender;
			this.closeConnection = closeConnection;
			this.sourceAddress = sourceAddress;
			this.connectedAtMillis = now;
			this.lastActivityMillis = now;
			this.requestWindowStartedMillis = now;
		}
	}

	private static final class AuthenticationChallenge
	{
		private final String id;
		private final String ign;
		private final byte[] publicKey;
		private final byte[] nonce;
		private final long expiresAtMillis;

		private AuthenticationChallenge(String id, String ign, byte[] publicKey, byte[] nonce, long expiresAtMillis)
		{
			this.id = id;
			this.ign = ign;
			this.publicKey = publicKey.clone();
			this.nonce = nonce.clone();
			this.expiresAtMillis = expiresAtMillis;
		}
	}

	private static final class Lobby
	{
		private final String code;
		private final Session owner;
		private final Deck ownerDeck;

		private Lobby(String code, Session owner, Deck ownerDeck)
		{
			this.code = code;
			this.owner = owner;
			this.ownerDeck = ownerDeck;
		}
	}
}
