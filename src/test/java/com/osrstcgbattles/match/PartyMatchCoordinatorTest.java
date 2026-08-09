package com.osrstcgbattles.match;

import com.google.gson.Gson;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidator;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.integration.CatalogCardLookup;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.party.BattlePartyMessageType;
import com.osrstcgbattles.party.CanonicalPartyJsonCodec;
import com.osrstcgbattles.party.DeckCommitment;
import com.osrstcgbattles.party.PartyApplicationMessage;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartyMatchCoordinatorTest
{
	@Test
	public void retriesDroppedSeedCommitment()
	{
		Harness harness = new Harness();
		harness.firstChannel.drop(BattlePartyMessageType.SNAPSHOT, "SEED_COMMIT", 1);

		harness.startAndDrain();
		assertEquals(PartyMatchSnapshot.Status.WAITING_SETUP, harness.second.snapshot().getStatus());

		harness.tickAndDrain();

		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertTrue(harness.firstChannel.count(BattlePartyMessageType.SNAPSHOT, "SEED_COMMIT") >= 2);
	}

	@Test
	public void retriesDroppedSetupReveal()
	{
		Harness harness = new Harness();
		harness.firstChannel.drop(BattlePartyMessageType.SNAPSHOT, "SETUP", 1);

		harness.startAndDrain();
		assertEquals(PartyMatchSnapshot.Status.WAITING_SETUP, harness.second.snapshot().getStatus());

		harness.tickAndDrain();

		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertTrue(harness.firstChannel.count(BattlePartyMessageType.SNAPSHOT, "SETUP") >= 2);
	}

	@Test
	public void duplicateHandshakeMessagesAreIdempotent()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		Sent seed = harness.firstChannel.find(BattlePartyMessageType.SNAPSHOT, "SEED_COMMIT");
		Sent setup = harness.firstChannel.find(BattlePartyMessageType.SNAPSHOT, "SETUP");

		harness.deliver(harness.second, 1, seed);
		harness.deliver(harness.second, 1, setup);
		harness.drain();

		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertEquals(0, harness.first.snapshot().getRevision());
		assertEquals(0, harness.second.snapshot().getRevision());
	}

	@Test
	public void retriesRetainedActionAfterDrop()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		harness.firstChannel.drop(BattlePartyMessageType.ACTION, null, 1);

		harness.first.submit(new FinishMulliganCommand(harness.first.snapshot().getLocalSeat()));
		harness.drain();
		assertEquals(1, harness.first.snapshot().getRevision());
		assertEquals(0, harness.second.snapshot().getRevision());

		harness.tickAndDrain();

		assertEquals(1, harness.second.snapshot().getRevision());
		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertTrue(harness.firstChannel.count(BattlePartyMessageType.ACTION, null) >= 2);
	}

	@Test
	public void duplicateAppliedActionResendsAckAndDuplicateAckIsIgnored()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		harness.first.submit(new FinishMulliganCommand(harness.first.snapshot().getLocalSeat()));
		Sent action = harness.firstChannel.find(BattlePartyMessageType.ACTION, null);
		harness.drain();
		Sent ack = harness.secondChannel.find(BattlePartyMessageType.ACTION_ACK, null);
		int ackCount = harness.secondChannel.count(BattlePartyMessageType.ACTION_ACK, null);

		harness.deliver(harness.second, 1, action);
		harness.deliver(harness.first, 2, ack);
		harness.drain();

		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertEquals(1, harness.second.snapshot().getRevision());
		assertTrue(harness.secondChannel.count(BattlePartyMessageType.ACTION_ACK, null) > ackCount);
	}

	@Test
	public void droppedAckIsRecoveredByAckAndActionRetries()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		harness.secondChannel.drop(BattlePartyMessageType.ACTION_ACK, null, 2);

		harness.first.submit(new FinishMulliganCommand(harness.first.snapshot().getLocalSeat()));
		harness.drain();
		harness.tickAndDrain();

		harness.assertBoth(PartyMatchSnapshot.Status.ACTIVE);
		assertTrue(harness.firstChannel.count(BattlePartyMessageType.ACTION, null) >= 2);
		assertTrue(harness.secondChannel.count(BattlePartyMessageType.ACTION_ACK, null) >= 3);
	}

	@Test
	public void terminalActionRemainsCompleteWhenDuplicatedOrFollowedByBadAction()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		harness.first.submit(new ConcedeCommand(harness.first.snapshot().getLocalSeat()));
		Sent concede = harness.firstChannel.find(BattlePartyMessageType.ACTION, null);
		harness.drain();

		harness.deliver(harness.second, 1, concede);
		Map<String, Object> bad = new LinkedHashMap<>(concede.payload);
		bad.put("priorRevision", 50L);
		harness.deliver(harness.second, 1, new Sent(BattlePartyMessageType.ACTION, bad));
		harness.drain();

		harness.assertBoth(PartyMatchSnapshot.Status.COMPLETE);
	}

	@Test
	public void abortsWithClearStatusAfterBoundedSetupTimeout()
	{
		Harness harness = new Harness();
		harness.firstChannel.drop(BattlePartyMessageType.SNAPSHOT, "SEED_COMMIT", 100);
		harness.secondChannel.drop(BattlePartyMessageType.SNAPSHOT, "SEED_COMMIT", 100);
		harness.startAndDrain();

		for (int i = 0; i <= PartyMatchCoordinator.RETRY_LIMIT; i++) harness.tickAndDrain();

		harness.assertBoth(PartyMatchSnapshot.Status.ABORTED);
		assertEquals("Match synchronization timed out", harness.first.snapshot().getUserStatus());
	}

	@Test
	public void abortsAfterActionAndAckRetryBudgetIsExhausted()
	{
		Harness harness = new Harness();
		harness.startAndDrain();
		harness.firstChannel.drop(BattlePartyMessageType.ACTION, null, 100);
		harness.first.submit(new FinishMulliganCommand(harness.first.snapshot().getLocalSeat()));

		for (int i = 0; i <= PartyMatchCoordinator.RETRY_LIMIT; i++) harness.tickAndDrain();

		assertEquals(PartyMatchSnapshot.Status.ABORTED, harness.first.snapshot().getStatus());
		assertEquals("Match synchronization timed out", harness.first.snapshot().getUserStatus());
		assertEquals(PartyMatchCoordinator.RETRY_LIMIT + 1,
			harness.firstChannel.count(BattlePartyMessageType.ACTION, null));
	}

	private static final class Harness
	{
		private static final String MATCH_ID = "match-test";
		private final BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();
		private final Deck firstDeck = deck("first-deck", catalog);
		private final Deck secondDeck = deck("second-deck", catalog);
		private final FakeChannel firstChannel = new FakeChannel();
		private final FakeChannel secondChannel = new FakeChannel();
		private final PartyMatchCoordinator first = coordinator(1, 2, firstDeck, secondDeck, firstChannel, 11L);
		private final PartyMatchCoordinator second = coordinator(2, 1, secondDeck, firstDeck, secondChannel, 22L);
		private long sequence;

		private PartyMatchCoordinator coordinator(long localId, long peerId, Deck localDeck, Deck peerDeck,
			PartyMatchChannel channel, long contribution)
		{
			CatalogCardLookup lookup = new CatalogCardLookup(catalog);
			return new PartyMatchCoordinator(localId, duel(peerId, localDeck, peerDeck), catalog,
				new DeckValidator(), lookup, new CatalogDeckFactory(catalog), localDeck, channel, contribution);
		}

		private PartyDuelSnapshot duel(long peerId, Deck localDeck, Deck peerDeck)
		{
			try
			{
				Constructor<?> constructor = null;
				for (Constructor<?> candidate : PartyDuelSnapshot.class.getDeclaredConstructors())
					if (candidate.getParameterCount() == 14) constructor = candidate;
				if (constructor == null) throw new AssertionError("READY snapshot constructor not found");
				constructor.setAccessible(true);
				return (PartyDuelSnapshot) constructor.newInstance(PartyDuelSnapshot.Status.READY, MATCH_ID, peerId,
					"Peer", "Ready", false, false, true, catalog.getSha256(), catalog.getRulesetVersion(),
					localDeck.getId(), DeckCommitment.compute(catalog.getSha256(), catalog.getRulesetVersion(), localDeck),
					peerDeck.getId(), DeckCommitment.compute(catalog.getSha256(), catalog.getRulesetVersion(), peerDeck));
			}
			catch (ReflectiveOperationException exception)
			{
				throw new AssertionError(exception);
			}
		}

		private void startAndDrain()
		{
			first.start();
			second.start();
			drain();
		}

		private void tickAndDrain()
		{
			first.onTick();
			second.onTick();
			drain();
		}

		private void drain()
		{
			while (!firstChannel.outbound.isEmpty() || !secondChannel.outbound.isEmpty())
			{
				while (!firstChannel.outbound.isEmpty()) deliver(second, 1, firstChannel.outbound.remove(0));
				while (!secondChannel.outbound.isEmpty()) deliver(first, 2, secondChannel.outbound.remove(0));
			}
		}

		private void deliver(PartyMatchCoordinator recipient, long senderId, Sent sent)
		{
			recipient.receive(new PartyApplicationMessage(sent.type, MATCH_ID, senderId, ++sequence, sent.payload,
				new CanonicalPartyJsonCodec(new Gson())));
		}

		private void assertBoth(PartyMatchSnapshot.Status status)
		{
			assertEquals(status, first.snapshot().getStatus());
			assertEquals(status, second.snapshot().getStatus());
		}
	}

	private static Deck deck(String id, BattleCardCatalog catalog)
	{
		List<BattleCard> cards = new ArrayList<>(catalog.getCards());
		cards.sort(Comparator.comparing(BattleCard::getId));
		List<DeckEntry> entries = new ArrayList<>();
		int remaining = DeckValidator.MAX_CARDS;
		for (BattleCard card : cards)
		{
			if (remaining == 0) break;
			int count = Math.min(remaining, DeckValidator.copyLimit(new CatalogCardLookup(catalog)
				.findById(card.getId()).get()));
			entries.add(new DeckEntry(card.getId(), count));
			remaining -= count;
		}
		if (remaining != 0) throw new AssertionError("catalog cannot build a test deck");
		return new Deck(id, id, entries);
	}

	private static final class FakeChannel implements PartyMatchChannel
	{
		private final List<Sent> outbound = new ArrayList<>();
		private final List<Sent> history = new ArrayList<>();
		private final Map<String, Integer> drops = new HashMap<>();

		@Override
		public boolean send(BattlePartyMessageType type, Map<String, ?> payload)
		{
			Sent sent = new Sent(type, payload);
			history.add(sent);
			String key = key(type, kind(payload));
			int remaining = drops.getOrDefault(key, 0);
			if (remaining > 0)
			{
				drops.put(key, remaining - 1);
				return true;
			}
			outbound.add(sent);
			return true;
		}

		private void drop(BattlePartyMessageType type, String kind, int count)
		{
			drops.put(key(type, kind), count);
		}

		private int count(BattlePartyMessageType type, String kind)
		{
			int count = 0;
			for (Sent sent : history)
				if (sent.type == type && (kind == null || kind.equals(kind(sent.payload)))) count++;
			return count;
		}

		private Sent find(BattlePartyMessageType type, String kind)
		{
			for (Sent sent : history)
				if (sent.type == type && (kind == null || kind.equals(kind(sent.payload)))) return sent;
			throw new AssertionError("message not found: " + type + " " + kind);
		}

		private static String kind(Map<String, ?> payload)
		{
			Object value = payload.get("kind");
			return value instanceof String ? (String) value : null;
		}

		private static String key(BattlePartyMessageType type, String kind)
		{
			return type.name() + ":" + kind;
		}
	}

	private static final class Sent
	{
		private final BattlePartyMessageType type;
		private final Map<String, Object> payload;

		private Sent(BattlePartyMessageType type, Map<String, ?> payload)
		{
			this.type = type;
			this.payload = new LinkedHashMap<>();
			for (Map.Entry<String, ?> entry : payload.entrySet()) this.payload.put(entry.getKey(), entry.getValue());
		}
	}
}
