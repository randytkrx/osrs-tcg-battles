package com.osrstcgbattles.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PartyBattleParticipantTest
{
	@Test
	public void cachedPartyNameWinsWhenAvailable()
	{
		PartyBattleParticipant participant = new PartyBattleParticipant(7L, " Party Hub Name ",
			"Snapshot Name", null);

		assertEquals(7L, participant.getMemberId());
		assertEquals("Party Hub Name", participant.getDisplayName());
	}

	@Test
	public void snapshotNameReplacesMissingPartyName()
	{
		assertEquals("Snapshot Name",
			new PartyBattleParticipant(7L, "<unknown>", " Snapshot Name ", null).getDisplayName());
		assertNull(new PartyBattleParticipant(7L, " ", null, null).getDisplayName());
	}
}
