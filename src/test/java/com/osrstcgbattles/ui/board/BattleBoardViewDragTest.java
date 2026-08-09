package com.osrstcgbattles.ui.board;

import com.google.gson.Gson;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.GwentEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.UnitCard;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BattleBoardViewDragTest
{
	@Test
	public void draggingPlayableCardExecutesAndRebuildsHandWithoutCrashing() throws Exception
	{
		GwentEngine engine = new GwentEngine();
		AtomicReference<MatchState> state = new AtomicReference<>(engine.newMatch(deck(), deck(), 1L));
		BattleBoardView view = new BattleBoardView(PlayerId.PLAYER_ONE,
			new BattleCardCatalogLoader(new Gson()).loadDefault(), new NoCardArtProvider());
		BattleBoardPanel root = (BattleBoardPanel) view.getComponent();
		root.setSize(1100, 750);
		root.doLayout();
		view.setState(state.get());
		view.setCommandListener(command ->
		{
			CommandResult result = engine.execute(state.get(), command);
			assertTrue(result.isAccepted());
			state.set(result.getState());
			view.setState(result.getState());
		});

		HandPanel hand = root.getLocalHand();
		hand.doLayout();
		JComponent tile = hand.tileAt(0);
		Point drop = new Point(root.getBoardPanel().getBounds().x + 40,
			root.getBoardPanel().getBounds().y + root.getBoardPanel().getHeight() / 2);
		Point onTile = SwingUtilities.convertPoint(root, drop, tile);
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_PRESSED, 1L,
			InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_DRAGGED, 2L,
			InputEvent.BUTTON1_DOWN_MASK, onTile.x, onTile.y, 0, false, MouseEvent.NOBUTTON));
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_RELEASED, 3L,
			0, onTile.x, onTile.y, 1, false, MouseEvent.BUTTON1));
		SwingUtilities.invokeAndWait(() -> { });

		assertEquals(5, state.get().getPlayer(PlayerId.PLAYER_ONE).getHand().size());
		assertEquals(1, state.get().getBoard().getUnits(PlayerId.PLAYER_ONE).size());
	}

	private static List<Card> deck()
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < GwentEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard("neutral-chicken", "Chicken", 0, 1, 1));
		}
		return cards;
	}
}
