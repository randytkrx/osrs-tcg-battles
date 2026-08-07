package com.osrstcgbattles.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartyBattleWindowTest
{
	@Test
	public void userCloseCanOnlyBeClaimedOnce()
	{
		AtomicBoolean closed = new AtomicBoolean();
		assertTrue(PartyBattleWindow.claimUserClose(closed));
		assertFalse(PartyBattleWindow.claimUserClose(closed));
	}

	@Test
	public void ownerCloseActionRunsOnce()
	{
		AtomicBoolean invoked = new AtomicBoolean();
		AtomicInteger calls = new AtomicInteger();
		PartyBattleWindow.invokeOnce(invoked, calls::incrementAndGet);
		PartyBattleWindow.invokeOnce(invoked, calls::incrementAndGet);
		assertEquals(1, calls.get());
	}
}
