package com.osrstcgbattles.ui.board;

import com.google.gson.Gson;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayCardCommand;
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
		DuelscapeEngine engine = new DuelscapeEngine();
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

	@Test
	public void draggingReadyUnitOntoEnemyUnitAttacksAndRebuildsBoard() throws Exception
	{
		DuelscapeEngine engine = new DuelscapeEngine();
		MatchState prepared = accepted(engine, engine.newMatch(deck(), deck(), 2L),
			new PlayCardCommand(PlayerId.PLAYER_ONE, "neutral-chicken"));
		prepared = accepted(engine, prepared, new EndTurnCommand(PlayerId.PLAYER_ONE));
		prepared = accepted(engine, prepared, new PlayCardCommand(PlayerId.PLAYER_TWO, "neutral-chicken"));
		prepared = accepted(engine, prepared, new EndTurnCommand(PlayerId.PLAYER_TWO));
		AtomicReference<MatchState> state = new AtomicReference<>(prepared);
		AtomicReference<AttackCommand> attack = new AtomicReference<>();
		BattleBoardView view = view(state, engine, attack);
		BattleBoardPanel root = (BattleBoardPanel) view.getComponent();
		String attacker = state.get().getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();
		String target = state.get().getBoard().getUnits(PlayerId.PLAYER_TWO).get(0).getInstanceId();

		dragUnit(root, attacker, centerOnRoot(root, target));

		assertEquals(attacker, attack.get().getAttackerInstanceId());
		assertEquals(target, attack.get().getTargetInstanceId().get());
		assertTrue(root.getBattleLog().text().contains("You attacked"));
		assertTrue(root.getBattleLog().text().contains("was defeated"));
	}

	@Test
	public void draggingReadyUnitOntoEnemyHeroAttacksHero() throws Exception
	{
		DuelscapeEngine engine = new DuelscapeEngine();
		MatchState prepared = accepted(engine, engine.newMatch(deck(), deck(), 3L),
			new PlayCardCommand(PlayerId.PLAYER_ONE, "neutral-chicken"));
		prepared = accepted(engine, prepared, new EndTurnCommand(PlayerId.PLAYER_ONE));
		prepared = accepted(engine, prepared, new EndTurnCommand(PlayerId.PLAYER_TWO));
		AtomicReference<MatchState> state = new AtomicReference<>(prepared);
		AtomicReference<AttackCommand> attack = new AtomicReference<>();
		BattleBoardView view = view(state, engine, attack);
		BattleBoardPanel root = (BattleBoardPanel) view.getComponent();
		String attacker = state.get().getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();
		Point hero = new Point(root.getOpponentHero().getX() + root.getOpponentHero().getWidth() / 2,
			root.getOpponentHero().getY() + root.getOpponentHero().getHeight() / 2);

		dragUnit(root, attacker, hero);

		assertEquals(attacker, attack.get().getAttackerInstanceId());
		assertTrue(!attack.get().getTargetInstanceId().isPresent());
		assertTrue(root.getBattleLog().text().contains("enemy hero"));
		assertTrue(root.getBattleLog().text().contains("Opponent took 1 damage"));
	}

	private static BattleBoardView view(AtomicReference<MatchState> state, DuelscapeEngine engine,
		AtomicReference<AttackCommand> attack)
	{
		BattleBoardView view = new BattleBoardView(PlayerId.PLAYER_ONE,
			new BattleCardCatalogLoader(new Gson()).loadDefault(), new NoCardArtProvider());
		BattleBoardPanel root = (BattleBoardPanel) view.getComponent();
		root.setSize(1100, 750);
		root.doLayout();
		view.setState(state.get());
		layoutBoard(root);
		view.setCommandListener(command ->
		{
			attack.set((AttackCommand) command);
			CommandResult result = engine.execute(state.get(), command);
			assertTrue(result.isAccepted());
			state.set(result.getState());
			view.setState(result.getState());
		});
		return view;
	}

	private static void dragUnit(BattleBoardPanel root, String attackerId, Point drop) throws Exception
	{
		layoutBoard(root);
		CardTile tile = root.getBoardPanel().tileFor(attackerId);
		Point onTile = SwingUtilities.convertPoint(root, drop, tile);
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_PRESSED, 1L,
			InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_DRAGGED, 2L,
			InputEvent.BUTTON1_DOWN_MASK, onTile.x, onTile.y, 0, false, MouseEvent.NOBUTTON));
		tile.dispatchEvent(new MouseEvent(tile, MouseEvent.MOUSE_RELEASED, 3L,
			0, onTile.x, onTile.y, 1, false, MouseEvent.BUTTON1));
		SwingUtilities.invokeAndWait(() -> { });
	}

	private static Point centerOnRoot(BattleBoardPanel root, String instanceId)
	{
		java.awt.Rectangle bounds = root.getBoardPanel().tileBoundsOnBoard(instanceId);
		Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		return SwingUtilities.convertPoint(root.getBoardPanel(), center, root);
	}

	private static void layoutBoard(BattleBoardPanel root)
	{
		root.doLayout();
		BoardPanel board = root.getBoardPanel();
		board.doLayout();
		for (PlayerId player : PlayerId.values())
		{
			java.awt.Rectangle bounds = board.bandBounds(player);
			if (bounds != null)
			{
				((JComponent) board.getComponent(player == PlayerId.PLAYER_TWO ? 0 : 1)).doLayout();
			}
		}
	}

	private static MatchState accepted(DuelscapeEngine engine, MatchState state, com.osrstcgbattles.engine.Command command)
	{
		CommandResult result = engine.execute(state, command);
		assertTrue(result.isAccepted());
		return result.getState();
	}

	private static List<Card> deck()
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard("neutral-chicken", "Chicken", 0, 1, 1));
		}
		return cards;
	}
}
