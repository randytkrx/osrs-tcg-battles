package com.osrstcgbattles.party;

import com.google.gson.Gson;
import com.osrstcgbattles.engine.DuelscapeEngine;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattlePartyCoordinatorTest
{
	private static final long FIRST = 11L;
	private static final long SECOND = 22L;
	private static final PartyDuelMetadata METADATA = new PartyDuelMetadata(repeat('a'),
		DuelscapeEngine.RULESET_VERSION, "deck", repeat('b'));

	@Test
	public void retriesDroppedHandshakeEnvelopeWithSameIdentity()
	{
		BattlePartyCoordinator coordinator = new BattlePartyCoordinator(FIRST, new Gson());
		coordinator.invite(SECOND, "peer", METADATA, 0L);
		BattlePartyEnvelope initial = coordinator.drainOutbound().get(0);

		coordinator.onTick(2_000_000_000L);
		BattlePartyEnvelope retry = coordinator.drainOutbound().get(0);

		assertEquals(initial.getMessageId(), retry.getMessageId());
		assertEquals(initial.getSequence(), retry.getSequence());
	}

	@Test
	public void acceptedProgressExtendsTheCurrentHandshakeDeadline()
	{
		BattlePartyCoordinator first = new BattlePartyCoordinator(FIRST, new Gson());
		BattlePartyCoordinator second = new BattlePartyCoordinator(SECOND, new Gson());
		first.invite(SECOND, "second", METADATA, 0L);
		transfer(first, FIRST, second, 9_000_000_000L);
		transfer(second, SECOND, first, 9_000_000_000L);

		second.accept(METADATA);
		transfer(second, SECOND, first, 59_000_000_000L);
		first.onTick(65_000_000_000L);

		assertFalse(first.snapshot().getStatus() == PartyDuelSnapshot.Status.TERMINAL);
	}

	@Test
	public void peersDeriveTheSameUserVerifiableAuthenticationCode()
	{
		BattlePartyCoordinator first = new BattlePartyCoordinator(FIRST, new Gson());
		BattlePartyCoordinator second = new BattlePartyCoordinator(SECOND, new Gson());
		first.invite(SECOND, "second", METADATA, 0L);
		transfer(first, FIRST, second, 1L);
		transfer(second, SECOND, first, 2L);
		second.accept(METADATA);
		for (long now = 3; now < 20 && (first.snapshot().getStatus() != PartyDuelSnapshot.Status.READY
			|| second.snapshot().getStatus() != PartyDuelSnapshot.Status.READY); now++)
		{
			transfer(first, FIRST, second, now);
			transfer(second, SECOND, first, now);
		}

		assertEquals(PartyDuelSnapshot.Status.READY, first.snapshot().getStatus());
		assertEquals(PartyDuelSnapshot.Status.READY, second.snapshot().getStatus());
		assertEquals(first.snapshot().getUserStatus(), second.snapshot().getUserStatus());
		assertTrue(first.snapshot().getUserStatus().matches("Verify security code with opponent: \\d{3}-\\d{3}"));
	}

	@Test
	public void retryingPendingApplicationRequeuesMissingPredecessorsInOrder()
	{
		BattlePartyCoordinator first = new BattlePartyCoordinator(FIRST, new Gson());
		BattlePartyCoordinator second = new BattlePartyCoordinator(SECOND, new Gson());
		makeReady(first, second);

		first.sendApplication(BattlePartyMessageType.ACTION, Collections.singletonMap("command", "N"));
		BattlePartyEnvelope missing = first.drainOutbound().get(0);
		first.sendApplication(BattlePartyMessageType.ACTION, Collections.singletonMap("command", "N+1"));
		List<BattlePartyEnvelope> initial = first.drainOutbound();
		BattlePartyEnvelope later = initial.get(1);

		assertEquals(missing.getSequence() + 1, later.getSequence());
		assertEquals(BattlePartyCoordinator.ReceiveResult.GAP,
			second.receive(FIRST, "first", true, later, 20L));

		first.sendApplication(BattlePartyMessageType.ACTION, Collections.singletonMap("command", "N+1"));
		List<BattlePartyEnvelope> retry = first.drainOutbound();
		assertEquals(Arrays.asList(missing.getSequence(), later.getSequence()),
			Arrays.asList(retry.get(0).getSequence(), retry.get(1).getSequence()));
		assertEquals(missing.getMessageId(), retry.get(0).getMessageId());
		assertEquals(later.getMessageId(), retry.get(1).getMessageId());
		assertEquals(BattlePartyCoordinator.ReceiveResult.ACCEPTED,
			second.receive(FIRST, "first", true, retry.get(0), 21L));
		assertEquals(BattlePartyCoordinator.ReceiveResult.ACCEPTED,
			second.receive(FIRST, "first", true, retry.get(1), 22L));

		List<PartyApplicationMessage> recovered = second.drainInboundApplication();
		assertEquals(2, recovered.size());
		assertEquals("N", recovered.get(0).getPayload().get("command"));
		assertEquals("N+1", recovered.get(1).getPayload().get("command"));
	}

	@Test
	public void protocolVersionExposesOnlyTheCurrentMessageSurface()
	{
		assertEquals(3, BattlePartyEnvelope.CURRENT_PROTOCOL_VERSION);
		assertArrayEquals(new String[] {"INVITE", "INVITE_ACK", "ACCEPT", "DECLINE", "KEY", "READY",
			"ACTION", "ACTION_ACK", "SNAPSHOT", "ABORT"},
			Arrays.stream(BattlePartyMessageType.values()).map(Enum::name).toArray(String[]::new));
	}

	private static void makeReady(BattlePartyCoordinator first, BattlePartyCoordinator second)
	{
		first.invite(SECOND, "second", METADATA, 0L);
		transfer(first, FIRST, second, 1L);
		transfer(second, SECOND, first, 2L);
		second.accept(METADATA);
		for (long now = 3; now < 20 && (first.snapshot().getStatus() != PartyDuelSnapshot.Status.READY
			|| second.snapshot().getStatus() != PartyDuelSnapshot.Status.READY); now++)
		{
			transfer(first, FIRST, second, now);
			transfer(second, SECOND, first, now);
		}
		assertEquals(PartyDuelSnapshot.Status.READY, first.snapshot().getStatus());
		assertEquals(PartyDuelSnapshot.Status.READY, second.snapshot().getStatus());
		first.drainOutbound();
		second.drainOutbound();
	}

	private static void transfer(BattlePartyCoordinator source, long sourceId,
		BattlePartyCoordinator target, long now)
	{
		List<BattlePartyEnvelope> messages = source.drainOutbound();
		for (BattlePartyEnvelope message : messages)
		{
			target.receive(sourceId, "peer", true, message, now);
		}
	}

	private static String repeat(char character)
	{
		return String.valueOf(character).repeat(64);
	}
}
