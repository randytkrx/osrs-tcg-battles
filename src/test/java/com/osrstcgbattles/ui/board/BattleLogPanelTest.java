package com.osrstcgbattles.ui.board;

import com.google.gson.Gson;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattleLogPanelTest
{
	@Test
	public void toggleOpensCompactLogAboveConcedeControl()
	{
		BattleBoardPanel board = new BattleBoardPanel(new BattleCardCatalogLoader(new Gson()).loadDefault(),
			new NoCardArtProvider());
		board.setSize(1100, 750);
		board.doLayout();

		assertFalse(board.getBattleLog().isVisible());
		assertTrue(board.getBattleLogButton().getY() < board.getConcedeButton().getY());
		board.getBattleLogButton().doClick();
		assertTrue(board.getBattleLog().isVisible());
		assertTrue(board.getBattleLog().getWidth() >= 180);
		assertTrue(board.getBattleLog().getY() + board.getBattleLog().getHeight()
			< board.getBattleLogButton().getY());
	}

	@Test
	public void logKeepsRecentPublicEvents()
	{
		BattleLogPanel log = new BattleLogPanel();
		for (int i = 0; i < 81; i++) log.append("Event " + i);

		assertFalse(log.text().contains("Event 0\n"));
		assertTrue(log.text().contains("Event 80"));
	}
}
