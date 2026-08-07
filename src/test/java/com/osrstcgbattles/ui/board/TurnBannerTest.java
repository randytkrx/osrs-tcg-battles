package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TurnBannerTest
{
	@Test
	public void onlyMeaningfulActivePlayTransitionsShow()
	{
		assertFalse(shouldShow(false, MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE,
			MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_TWO));
		assertFalse(shouldShow(true, MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE,
			MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE));
		assertTrue(shouldShow(true, MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE,
			MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_TWO));
		assertTrue(shouldShow(true, MatchStatus.ACTIVE, MatchPhase.MULLIGAN, PlayerId.PLAYER_ONE,
			MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE));
	}

	@Test
	public void mulliganAndCompleteNeverShow()
	{
		assertFalse(shouldShow(true, MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE,
			MatchStatus.ACTIVE, MatchPhase.MULLIGAN, PlayerId.PLAYER_TWO));
		assertFalse(shouldShow(true, MatchStatus.ACTIVE, MatchPhase.PLAY, PlayerId.PLAYER_ONE,
			MatchStatus.COMPLETE, MatchPhase.COMPLETE, null));
	}

	private static boolean shouldShow(boolean hasPrevious, MatchStatus previousStatus,
		MatchPhase previousPhase, PlayerId previousActive, MatchStatus nextStatus,
		MatchPhase nextPhase, PlayerId nextActive)
	{
		return TurnBanner.shouldShow(hasPrevious, previousStatus, previousPhase, previousActive,
			nextStatus, nextPhase, nextActive);
	}
}
