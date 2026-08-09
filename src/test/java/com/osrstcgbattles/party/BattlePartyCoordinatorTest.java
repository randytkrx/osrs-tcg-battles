package com.osrstcgbattles.party;

import com.google.gson.Gson;
import com.osrstcgbattles.engine.GwentEngine;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattlePartyCoordinatorTest
{
	private static final long FIRST = 11L;
	private static final long SECOND = 22L;
	private static final PartyDuelMetadata METADATA = new PartyDuelMetadata(repeat('a'),
		GwentEngine.RULESET_VERSION, "deck", repeat('b'));

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
