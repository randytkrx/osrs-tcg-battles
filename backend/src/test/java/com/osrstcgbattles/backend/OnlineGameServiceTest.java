package com.osrstcgbattles.backend;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.online.OnlineEnvelope;
import com.osrstcgbattles.online.OnlineMessageType;
import com.osrstcgbattles.online.RankedAuthentication;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class OnlineGameServiceTest
{
	@Test
	public void privateLobbyStartsAuthoritativeMatchAndDeduplicatesCommands()
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> hostMessages = new ArrayList<>();
		List<OnlineEnvelope> guestMessages = new ArrayList<>();
		String host = service.connect(hostMessages::add);
		String guest = service.connect(guestMessages::add);
		hello(service, host, "Host", hostMessages);
		hello(service, guest, "Guest", guestMessages);
		Deck hostDeck = new CatalogDeckFactory(service.getCatalog()).randomDeck();
		Deck guestDeck = new CatalogDeckFactory(service.getCatalog()).randomDeck();

		JsonObject create = new JsonObject();
		create.add("deck", deck(hostDeck));
		service.handle(host, message("create", OnlineMessageType.LOBBY_CREATE, create));
		String code = last(hostMessages, OnlineMessageType.LOBBY_CREATED).getPayload().get("code").getAsString();
		assertEquals(6, code.length());

		JsonObject join = new JsonObject();
		join.addProperty("code", code);
		join.add("deck", deck(guestDeck));
		service.handle(guest, message("join", OnlineMessageType.LOBBY_JOIN, join));

		OnlineEnvelope hostFound = last(hostMessages, OnlineMessageType.MATCH_FOUND);
		OnlineEnvelope guestFound = last(guestMessages, OnlineMessageType.MATCH_FOUND);
		assertEquals("PLAYER_ONE", hostFound.getPayload().get("seat").getAsString());
		assertEquals("PLAYER_TWO", guestFound.getPayload().get("seat").getAsString());
		String matchId = hostFound.getPayload().get("matchId").getAsString();
		assertEquals(matchId, guestFound.getPayload().get("matchId").getAsString());
		assertEquals(0, last(hostMessages, OnlineMessageType.MATCH_STATE).getPayload().get("revision").getAsLong());

		JsonObject command = command(matchId, "keep-one", 0, "FINISH_MULLIGAN");
		service.handle(host, message("action-one", OnlineMessageType.MATCH_COMMAND, command));
		assertEquals(1, last(hostMessages, OnlineMessageType.MATCH_STATE).getPayload().get("revision").getAsLong());
		assertEquals(1, last(guestMessages, OnlineMessageType.MATCH_STATE).getPayload().get("revision").getAsLong());

		service.handle(host, message("retry-one", OnlineMessageType.MATCH_COMMAND, command));
		assertEquals(1, last(hostMessages, OnlineMessageType.MATCH_STATE).getPayload().get("revision").getAsLong());
		assertFalse(contains(hostMessages, OnlineMessageType.MATCH_REJECTED));

		service.handle(guest, message("action-two", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "keep-two", 1, "FINISH_MULLIGAN")));
		JsonObject view = last(hostMessages, OnlineMessageType.MATCH_STATE).getPayload().getAsJsonObject("view");
		assertEquals("PLAY", view.get("phase").getAsString());
		assertTrue(view.has("localHand"));
		assertTrue(view.getAsJsonObject("players").getAsJsonObject("PLAYER_TWO").has("handSize"));
	}

	@Test
	public void disconnectedSessionCanResumeWithCurrentMatchState()
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> original = new ArrayList<>();
		String connection = service.connect(original::add);
		hello(service, connection, "Player", original);
		String token = last(original, OnlineMessageType.WELCOME).getPayload().get("reconnectToken").getAsString();
		service.disconnect(connection);

		List<OnlineEnvelope> resumed = new ArrayList<>();
		String replacement = service.connect(resumed::add);
		JsonObject payload = new JsonObject();
		payload.addProperty("reconnectToken", token);
		service.handle(replacement, message("resume", OnlineMessageType.RESUME, payload));

		OnlineEnvelope welcome = last(resumed, OnlineMessageType.WELCOME);
		assertTrue(welcome.getPayload().get("resumed").getAsBoolean());
		assertEquals(token, welcome.getPayload().get("reconnectToken").getAsString());
	}

	@Test
	public void connectedSessionCannotBeTakenOverAndReadyConnectionCannotResume()
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> originalMessages = new ArrayList<>();
		String original = service.connect(originalMessages::add);
		hello(service, original, "Player", originalMessages);
		String token = last(originalMessages, OnlineMessageType.WELCOME).getPayload()
			.get("reconnectToken").getAsString();

		List<OnlineEnvelope> attackerMessages = new ArrayList<>();
		String attacker = service.connect(attackerMessages::add);
		JsonObject resume = new JsonObject();
		resume.addProperty("reconnectToken", token);
		service.handle(attacker, message("live-takeover", OnlineMessageType.RESUME, resume));
		assertEquals("INVALID_REQUEST", last(attackerMessages, OnlineMessageType.ERROR)
			.getPayload().get("code").getAsString());

		service.disconnect(original);
		hello(service, attacker, "Attacker", attackerMessages);
		service.handle(attacker, message("ready-resume", OnlineMessageType.RESUME, resume));
		assertEquals("RESUME requires a fresh connection", last(attackerMessages, OnlineMessageType.ERROR)
			.getPayload().get("message").getAsString());
	}

	@Test
	public void casualQueuePairsTwoCompatiblePlayers()
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());

		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("queue-first", OnlineMessageType.QUEUE_JOIN, firstJoin));
		assertEquals("WAITING", last(firstMessages, OnlineMessageType.QUEUE_STATUS)
			.getPayload().get("status").getAsString());

		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(second, message("queue-second", OnlineMessageType.QUEUE_JOIN, secondJoin));

		assertEquals("PLAYER_ONE", last(firstMessages, OnlineMessageType.MATCH_FOUND)
			.getPayload().get("seat").getAsString());
		assertEquals("PLAYER_TWO", last(secondMessages, OnlineMessageType.MATCH_FOUND)
			.getPayload().get("seat").getAsString());

		String matchId = last(firstMessages, OnlineMessageType.MATCH_FOUND).getPayload().get("matchId").getAsString();
		service.handle(first, message("concede", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "concede-one", 0, "CONCEDE")));
		assertNotNull(last(firstMessages, OnlineMessageType.MATCH_COMPLETE));
		assertNotNull(last(secondMessages, OnlineMessageType.MATCH_COMPLETE));

		JsonObject acknowledged = new JsonObject();
		acknowledged.addProperty("matchId", matchId);
		acknowledged.addProperty("terminal", true);
		service.handle(first, message("match-ack", OnlineMessageType.MATCH_ACK, acknowledged));
		service.handle(first, message("queue-again", OnlineMessageType.QUEUE_JOIN, firstJoin));
		assertEquals("WAITING", last(firstMessages, OnlineMessageType.QUEUE_STATUS)
			.getPayload().get("status").getAsString());
	}

	@Test
	public void expiredReconnectTokenIsRemovedAndActiveMatchIsAborted()
	{
		AtomicLong clock = new AtomicLong(1_000_000L);
		OnlineGameService service = new OnlineGameService(new Gson(), clock::get);
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		String token = last(firstMessages, OnlineMessageType.WELCOME).getPayload()
			.get("reconnectToken").getAsString();
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("queue-first", OnlineMessageType.QUEUE_JOIN, firstJoin));
		service.handle(second, message("queue-second", OnlineMessageType.QUEUE_JOIN, secondJoin));

		service.disconnect(first);
		clock.addAndGet(120_001L);
		List<OnlineEnvelope> replacementMessages = new ArrayList<>();
		String replacement = service.connect(replacementMessages::add);
		assertEquals("Opponent did not reconnect", last(secondMessages, OnlineMessageType.MATCH_ABORTED)
			.getPayload().get("reason").getAsString());

		JsonObject resume = new JsonObject();
		resume.addProperty("reconnectToken", token);
		service.handle(replacement, message("expired-resume", OnlineMessageType.RESUME, resume));
		assertEquals("INVALID_REQUEST", last(replacementMessages, OnlineMessageType.ERROR)
			.getPayload().get("code").getAsString());
	}

	@Test
	public void authenticatedPlayersCompleteRankedMatchAndReceiveRatings() throws Exception
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		authenticate(service, first, "First", firstMessages);
		authenticate(service, second, "Second", secondMessages);

		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("ranked-first", OnlineMessageType.RANKED_QUEUE_JOIN, firstJoin));
		assertEquals("WAITING", last(firstMessages, OnlineMessageType.RANKED_QUEUE_STATUS)
			.getPayload().get("status").getAsString());
		service.handle(second, message("ranked-second", OnlineMessageType.RANKED_QUEUE_JOIN, secondJoin));

		String matchId = last(firstMessages, OnlineMessageType.MATCH_FOUND).getPayload().get("matchId").getAsString();
		assertTrue(last(firstMessages, OnlineMessageType.MATCH_FOUND).getPayload().get("ranked").getAsBoolean());
		service.handle(first, message("ranked-concede", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "ranked-concede-one", 0, "CONCEDE")));

		assertTrue(last(firstMessages, OnlineMessageType.RATING_UPDATE).getPayload().get("rating").getAsInt() < 1500);
		assertTrue(last(secondMessages, OnlineMessageType.RATING_UPDATE).getPayload().get("rating").getAsInt() > 1500);
	}

	@Test
	public void activePlayerConcedesWhenTurnTimerExpires()
	{
		AtomicLong clock = new AtomicLong(5_000_000L);
		OnlineGameService service = new OnlineGameService(new Gson(), clock::get);
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("queue-first", OnlineMessageType.QUEUE_JOIN, firstJoin));
		service.handle(second, message("queue-second", OnlineMessageType.QUEUE_JOIN, secondJoin));
		String matchId = last(firstMessages, OnlineMessageType.MATCH_FOUND).getPayload().get("matchId").getAsString();
		service.handle(first, message("mulligan-first", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "finish-first", 0, "FINISH_MULLIGAN")));
		service.handle(second, message("mulligan-second", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "finish-second", 1, "FINISH_MULLIGAN")));

		clock.addAndGet(180_001L);
		service.maintenance();

		assertNotNull(last(firstMessages, OnlineMessageType.MATCH_COMPLETE));
		assertNotNull(last(secondMessages, OnlineMessageType.MATCH_COMPLETE));
	}

	@Test
	public void rankedDisconnectAfterGraceRecordsAForfeit() throws Exception
	{
		AtomicLong clock = new AtomicLong(7_000_000L);
		OnlineGameService service = new OnlineGameService(new Gson(), clock::get);
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		authenticate(service, first, "First", firstMessages);
		authenticate(service, second, "Second", secondMessages);
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("ranked-first", OnlineMessageType.RANKED_QUEUE_JOIN, firstJoin));
		service.handle(second, message("ranked-second", OnlineMessageType.RANKED_QUEUE_JOIN, secondJoin));

		service.disconnect(first);
		clock.addAndGet(120_001L);
		service.maintenance();

		assertNotNull(last(secondMessages, OnlineMessageType.MATCH_COMPLETE));
		assertTrue(last(secondMessages, OnlineMessageType.RATING_UPDATE).getPayload().get("rating").getAsInt() > 1500);
	}

	@Test
	public void reconnectReceivesRankedTerminalStateAndRating() throws Exception
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		authenticate(service, first, "First", firstMessages);
		authenticate(service, second, "Second", secondMessages);
		String token = last(firstMessages, OnlineMessageType.WELCOME).getPayload()
			.get("reconnectToken").getAsString();
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("ranked-first", OnlineMessageType.RANKED_QUEUE_JOIN, firstJoin));
		service.handle(second, message("ranked-second", OnlineMessageType.RANKED_QUEUE_JOIN, secondJoin));
		String matchId = last(secondMessages, OnlineMessageType.MATCH_FOUND).getPayload().get("matchId").getAsString();

		service.disconnect(first);
		service.handle(second, message("second-concedes", OnlineMessageType.MATCH_COMMAND,
			command(matchId, "second-concedes", 0, "CONCEDE")));
		List<OnlineEnvelope> resumedMessages = new ArrayList<>();
		String replacement = service.connect(resumedMessages::add);
		JsonObject resume = new JsonObject();
		resume.addProperty("reconnectToken", token);
		service.handle(replacement, message("resume-terminal", OnlineMessageType.RESUME, resume));

		assertTrue(indexOf(resumedMessages, OnlineMessageType.MATCH_FOUND)
			< indexOf(resumedMessages, OnlineMessageType.MATCH_COMPLETE));
		assertNotNull(last(resumedMessages, OnlineMessageType.MATCH_COMPLETE));
		assertTrue(last(resumedMessages, OnlineMessageType.RATING_UPDATE).getPayload().get("rating").getAsInt() > 1500);

		JsonObject acknowledgement = new JsonObject();
		acknowledgement.addProperty("matchId", matchId);
		acknowledgement.addProperty("terminal", true);
		service.handle(replacement, message("terminal-ack", OnlineMessageType.MATCH_ACK, acknowledgement));
		service.disconnect(replacement);
		List<OnlineEnvelope> replayAfterAck = new ArrayList<>();
		String secondReplacement = service.connect(replayAfterAck::add);
		service.handle(secondReplacement, message("resume-after-ack", OnlineMessageType.RESUME, resume));
		assertFalse(contains(replayAfterAck, OnlineMessageType.MATCH_FOUND));
		assertFalse(contains(replayAfterAck, OnlineMessageType.MATCH_COMPLETE));
		assertFalse(contains(replayAfterAck, OnlineMessageType.RATING_UPDATE));
	}

	@Test
	public void waitingQueueExpiresBeforeItCanBeMatched()
	{
		AtomicLong clock = new AtomicLong(10_000_000L);
		OnlineGameService service = new OnlineGameService(new Gson(), clock::get);
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		hello(service, first, "First", firstMessages);
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("first-waits", OnlineMessageType.QUEUE_JOIN, firstJoin));

		clock.addAndGet(15 * 60 * 1000L + 1L);
		String second = service.connect(secondMessages::add);
		hello(service, second, "Second", secondMessages);
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(second, message("second-joins", OnlineMessageType.QUEUE_JOIN, secondJoin));

		assertEquals("EXPIRED", last(firstMessages, OnlineMessageType.QUEUE_STATUS)
			.getPayload().get("status").getAsString());
		assertEquals("WAITING", last(secondMessages, OnlineMessageType.QUEUE_STATUS)
			.getPayload().get("status").getAsString());
		assertFalse(contains(firstMessages, OnlineMessageType.MATCH_FOUND));
	}

	@Test
	public void oneRankedAccountCannotQueueFromTwoSessions() throws Exception
	{
		OnlineGameService service = new OnlineGameService(new Gson());
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "SameName", firstMessages);
		hello(service, second, "SameName", secondMessages);
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair keyPair = generator.generateKeyPair();
		authenticate(service, first, "SameName", firstMessages, keyPair);
		authenticate(service, second, "SameName", secondMessages, keyPair);
		JsonObject join = new JsonObject();
		join.add("deck", deck(new CatalogDeckFactory(service.getCatalog()).randomDeck()));
		service.handle(first, message("first-ranked", OnlineMessageType.RANKED_QUEUE_JOIN, join));
		service.handle(second, message("second-ranked", OnlineMessageType.RANKED_QUEUE_JOIN, join));

		assertEquals("ranked account is already queued or playing",
			last(secondMessages, OnlineMessageType.ERROR).getPayload().get("message").getAsString());
	}

	@Test
	public void simultaneousInitialMulliganTimeoutAbortsWithoutArbitraryWinner()
	{
		AtomicLong clock = new AtomicLong(9_000_000L);
		OnlineGameService service = new OnlineGameService(new Gson(), clock::get);
		List<OnlineEnvelope> firstMessages = new ArrayList<>();
		List<OnlineEnvelope> secondMessages = new ArrayList<>();
		String first = service.connect(firstMessages::add);
		String second = service.connect(secondMessages::add);
		hello(service, first, "First", firstMessages);
		hello(service, second, "Second", secondMessages);
		CatalogDeckFactory factory = new CatalogDeckFactory(service.getCatalog());
		JsonObject firstJoin = new JsonObject();
		firstJoin.add("deck", deck(factory.randomDeck()));
		JsonObject secondJoin = new JsonObject();
		secondJoin.add("deck", deck(factory.randomDeck()));
		service.handle(first, message("queue-first", OnlineMessageType.QUEUE_JOIN, firstJoin));
		service.handle(second, message("queue-second", OnlineMessageType.QUEUE_JOIN, secondJoin));

		clock.addAndGet(180_001L);
		service.maintenance();

		assertEquals("Both mulligan timers expired",
			last(secondMessages, OnlineMessageType.MATCH_ABORTED).getPayload().get("reason").getAsString());
		assertFalse(contains(secondMessages, OnlineMessageType.MATCH_COMPLETE));
	}

	private static void hello(OnlineGameService service, String connection, String name,
		List<OnlineEnvelope> messages)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("displayName", name);
		payload.addProperty("catalogHash", service.getCatalog().getSha256());
		payload.addProperty("rulesetVersion", DuelscapeEngine.RULESET_VERSION);
		service.handle(connection, message("hello-" + name, OnlineMessageType.HELLO, payload));
		assertNotNull(last(messages, OnlineMessageType.WELCOME));
	}

	private static void authenticate(OnlineGameService service, String connection, String ign,
		List<OnlineEnvelope> messages) throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		authenticate(service, connection, ign, messages, generator.generateKeyPair());
	}

	private static void authenticate(OnlineGameService service, String connection, String ign,
		List<OnlineEnvelope> messages, KeyPair keyPair) throws Exception
	{
		JsonObject begin = new JsonObject();
		begin.addProperty("ign", ign);
		begin.addProperty("publicKey", Base64.getUrlEncoder().withoutPadding()
			.encodeToString(keyPair.getPublic().getEncoded()));
		service.handle(connection, message("auth-begin-" + ign, OnlineMessageType.AUTH_BEGIN, begin));
		JsonObject challenge = last(messages, OnlineMessageType.AUTH_CHALLENGE).getPayload();
		String challengeId = challenge.get("challengeId").getAsString();
		byte[] nonce = Base64.getUrlDecoder().decode(challenge.get("nonce").getAsString());
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(RankedAuthentication.challenge("wss://play.deargod.live/v1/ws", challengeId, nonce, ign,
			keyPair.getPublic().getEncoded()));
		JsonObject response = new JsonObject();
		response.addProperty("challengeId", challengeId);
		response.addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
		service.handle(connection, message("auth-response-" + ign, OnlineMessageType.AUTH_RESPONSE, response));
		assertEquals(ign, last(messages, OnlineMessageType.AUTHENTICATED).getPayload().get("ign").getAsString());
	}

	private static JsonObject deck(Deck deck)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("deckId", deck.getId());
		JsonArray cards = new JsonArray();
		for (DeckEntry entry : deck.getEntries())
		{
			JsonObject card = new JsonObject();
			card.addProperty("cardId", entry.getCardId());
			card.addProperty("count", entry.getCount());
			cards.add(card);
		}
		payload.add("cards", cards);
		return payload;
	}

	private static JsonObject command(String matchId, String commandId, long revision, String type)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", matchId);
		payload.addProperty("commandId", commandId);
		payload.addProperty("expectedRevision", revision);
		JsonObject command = new JsonObject();
		command.addProperty("type", type);
		payload.add("command", command);
		return payload;
	}

	private static OnlineEnvelope message(String id, OnlineMessageType type, JsonObject payload)
	{
		return new OnlineEnvelope(id, type, payload);
	}

	private static OnlineEnvelope last(List<OnlineEnvelope> messages, OnlineMessageType type)
	{
		for (int i = messages.size() - 1; i >= 0; i--)
			if (messages.get(i).getType() == type) return messages.get(i);
		throw new AssertionError("Missing message: " + type);
	}

	private static boolean contains(List<OnlineEnvelope> messages, OnlineMessageType type)
	{
		for (OnlineEnvelope message : messages) if (message.getType() == type) return true;
		return false;
	}

	private static int indexOf(List<OnlineEnvelope> messages, OnlineMessageType type)
	{
		for (int i = 0; i < messages.size(); i++) if (messages.get(i).getType() == type) return i;
		return Integer.MAX_VALUE;
	}
}
