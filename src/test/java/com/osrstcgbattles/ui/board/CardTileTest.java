package com.osrstcgbattles.ui.board;

import com.google.gson.Gson;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CardTileTest
{
	@Test
	public void faceDownCardsNeverExposeRulesInTooltip()
	{
		BattleCard card = new BattleCardCatalogLoader(new Gson()).loadDefault()
			.findById("misthalin-dark-wizard").get();
		CardTile tile = new CardTile(card, new NoCardArtProvider());

		assertNotNull(tile.getToolTipText());
		assertTrue(tile.getToolTipText().contains(card.getRulesText()));

		tile.setFaceDown(true);
		assertNull(tile.getToolTipText());

		tile.setFaceDown(false);
		assertNotNull(tile.getToolTipText());
		assertTrue(tile.getToolTipText().contains(card.getRulesText()));
	}
}
