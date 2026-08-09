package com.osrstcgbattles.ui.board;

import com.google.gson.Gson;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.UnitCard;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HandPanelTest
{
	@Test
	public void faceDownHandUsesOnlyNeutralBacksFromCount()
	{
		HandPanel panel = new HandPanel(new BattleCardCatalogLoader(new Gson()).loadDefault(),
			new NoCardArtProvider(), HandPanel.Orientation.TOP);
		panel.setSize(560, 72);
		panel.setFaceDownCount(8);

		assertEquals(8, panel.renderedCardCount());
		assertTrue(panel.rendersOnlyFaceDownCards());
		for (int i = 0; i < panel.renderedCardCount(); i++)
		{
			assertTrue(panel.cardBounds(i).x >= 0);
			assertTrue(panel.cardBounds(i).x + panel.cardBounds(i).width <= panel.getWidth());
			assertTrue(panel.cardBounds(i).y + panel.cardBounds(i).height <= panel.getHeight());
		}
	}

	@Test
	public void handsCenterAndIncreaseOverlapWhenNarrowed()
	{
		HandPanel panel = new HandPanel(new BattleCardCatalogLoader(new Gson()).loadDefault(),
			new NoCardArtProvider(), HandPanel.Orientation.BOTTOM);
		panel.setFaceDownCount(10);
		panel.setSize(700, 140);
		panel.doLayout();
		Rectangle wideFirst = panel.cardBounds(0);
		Rectangle wideSecond = panel.cardBounds(1);
		Rectangle wideLast = panel.cardBounds(9);
		int wideStep = wideSecond.x - wideFirst.x;
		assertCentered(panel.getWidth(), wideFirst, wideLast);

		panel.setSize(400, 120);
		panel.doLayout();
		Rectangle narrowFirst = panel.cardBounds(0);
		Rectangle narrowSecond = panel.cardBounds(1);
		Rectangle narrowLast = panel.cardBounds(9);
		assertTrue(narrowSecond.x - narrowFirst.x < wideStep);
		assertCentered(panel.getWidth(), narrowFirst, narrowLast);
		assertTrue(narrowLast.x + narrowLast.width <= panel.getWidth());
		assertTrue(narrowLast.y + narrowLast.height <= panel.getHeight());
	}

	@Test
	public void localHoverRaisesEnlargesAndPromotesCorrectCard()
	{
		HandPanel panel = localHand();
		panel.setHand(cards());
		panel.setSize(420, 140);
		panel.doLayout();
		Rectangle before = panel.cardBounds(0);
		JComponent first = panel.tileAt(0);

		first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_ENTERED, 1L, 0, 4, 4, 0, false));

		Rectangle hovered = panel.cardBounds(0);
		assertTrue(hovered.y < before.y);
		assertTrue(hovered.width > before.width);
		assertEquals(0, panel.getComponentZOrder(first));

		first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, 2L, 0, 4, 4, 1, false,
			MouseEvent.BUTTON1));
		assertEquals("neutral-chicken", clicked.get());
	}

	@Test
	public void playableStateIsAppliedWithoutChangingHiddenCardPrivacy()
	{
		HandPanel panel = localHand();
		panel.setHand(cards());
		panel.setPlayableCardIds(Collections.singleton("neutral-chicken"));

		assertEquals(CardTile.State.PLAYABLE, ((CardTile) panel.tileAt(0)).getState());
		assertEquals(CardTile.State.DIMMED, ((CardTile) panel.tileAt(1)).getState());

		panel.setFaceDownCount(2);
		assertTrue(panel.rendersOnlyFaceDownCards());
	}

	@Test
	public void dragReportsCardAndDropPointOnTheBattleSurface() throws Exception
	{
		HandPanel panel = localHand();
		JPanel surface = new JPanel(null);
		surface.add(panel);
		panel.setBounds(100, 300, 420, 140);
		panel.setHand(cards());
		panel.doLayout();
		AtomicReference<String> dropped = new AtomicReference<>();
		AtomicReference<Point> dropPoint = new AtomicReference<>();
		panel.setCardDragListener(new HandPanel.CardDragListener()
		{
			@Override public void onCardDragged(String cardId, Point point) { }
			@Override public void onCardDropped(String cardId, Point point)
			{
				dropped.set(cardId);
				dropPoint.set(point);
			}
		});
		JComponent first = panel.tileAt(0);
		first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_PRESSED, 1L,
			InputEvent.BUTTON1_DOWN_MASK, 4, 4, 1, false, MouseEvent.BUTTON1));
		first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_DRAGGED, 2L,
			InputEvent.BUTTON1_DOWN_MASK, 30, -80, 0, false, MouseEvent.NOBUTTON));
		first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_RELEASED, 3L,
			0, 30, -80, 1, false, MouseEvent.BUTTON1));
		SwingUtilities.invokeAndWait(() -> { });

		assertEquals("neutral-chicken", dropped.get());
		assertTrue(dropPoint.get().y < panel.getY());
	}

	private final AtomicReference<String> clicked = new AtomicReference<>();

	private HandPanel localHand()
	{
		HandPanel panel = new HandPanel(new BattleCardCatalogLoader(new Gson()).loadDefault(),
			new NoCardArtProvider(), HandPanel.Orientation.BOTTOM);
		panel.setCardClickListener(clicked::set);
		return panel;
	}

	private static java.util.List<Card> cards()
	{
		return Arrays.asList(
			new UnitCard("neutral-chicken", "Chicken", 0, 1, 1),
			new UnitCard("misthalin-dark-wizard", "Dark Wizard", 2, 2, 2));
	}

	private static void assertCentered(int panelWidth, Rectangle first, Rectangle last)
	{
		int runCenter = (first.x + last.x + last.width) / 2;
		assertTrue(Math.abs(panelWidth / 2 - runCenter) <= 1);
	}
}
