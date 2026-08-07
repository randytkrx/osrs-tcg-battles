package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.PlayerId;
import java.awt.Rectangle;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BattleResultOverlayTest
{
	@Test
	public void resultTitleUsesLocalPerspective()
	{
		assertEquals("VICTORY", BattleResultOverlay.resultTitle(PlayerId.PLAYER_ONE, PlayerId.PLAYER_ONE));
		assertEquals("DEFEAT", BattleResultOverlay.resultTitle(PlayerId.PLAYER_ONE, PlayerId.PLAYER_TWO));
		assertEquals("DRAW", BattleResultOverlay.resultTitle(PlayerId.PLAYER_TWO, null));
	}

	@Test
	public void dialogGeometryIsCenteredAndContained()
	{
		assertGeometry(760, 560);
		assertGeometry(1100, 750);
		assertGeometry(1500, 900);
	}

	private static void assertGeometry(int width, int height)
	{
		Rectangle bounds = BattleResultOverlay.dialogBounds(width, height);
		assertTrue(bounds.width > 0 && bounds.height > 0);
		assertTrue(bounds.x >= 0 && bounds.y >= 0);
		assertTrue(bounds.x + bounds.width <= width);
		assertTrue(bounds.y + bounds.height <= height);
		assertEquals(width / 2, bounds.x + bounds.width / 2);
		assertEquals(height / 2, bounds.y + bounds.height / 2);
	}
}
