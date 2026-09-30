package com.osrstcgbattles.match;

import com.google.gson.Gson;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.UnitCard;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MatchViewTest
{
	@Test
	public void projectionContainsOnlyViewingPlayersHiddenCards()
	{
		MatchState state = new DuelscapeEngine().newMatch(deck("local-secret"), deck("opponent-secret"), 17L);

		MatchView view = MatchView.forPlayer(state, PlayerId.PLAYER_ONE);
		String serialized = new Gson().toJson(view);

		assertEquals(PlayerId.PLAYER_ONE, view.getViewer());
		assertEquals(state.getPlayer(PlayerId.PLAYER_ONE).getHand().size(), view.getLocalHand().size());
		assertEquals(state.getPlayer(PlayerId.PLAYER_TWO).getHand().size(),
			view.getPlayer(PlayerId.PLAYER_TWO).getHandSize());
		assertEquals(state.getPlayer(PlayerId.PLAYER_TWO).getDrawPile().size(),
			view.getPlayer(PlayerId.PLAYER_TWO).getDrawPileSize());
		assertTrue(serialized.contains("local-secret"));
		assertFalse(serialized.contains("opponent-secret"));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void localHandIsImmutable()
	{
		MatchState state = new DuelscapeEngine().newMatch(deck("first"), deck("second"), 23L);
		MatchView.forPlayer(state, PlayerId.PLAYER_ONE).getLocalHand().clear();
	}

	private static List<Card> deck(String id)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard(id, id, 0, 1, 1));
		}
		return cards;
	}
}
