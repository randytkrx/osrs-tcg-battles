package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Card;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/** Centered, adaptively overlapping hand for either edge of the battle surface. */
@Slf4j
public class HandPanel extends JPanel
{
	public enum Orientation
	{
		TOP,
		BOTTOM
	}

	private static final int SELECTED_RAISE = 10;
	private static final int HOVER_RAISE = 10;
	private static final int HOVER_GROW = 8;
	private static final int MIN_STEP = 13;

	private final BattleCardCatalog catalog;
	private final CardArtProvider art;
	private final Orientation orientation;
	private final List<JComponent> tiles = new ArrayList<>();
	private final List<String> cardIds = new ArrayList<>();

	private String selectedCardId;
	private JComponent hoveredTile;
	private Consumer<String> cardClickListener = cardId -> { };
	private Runnable cancelListener = () -> { };
	private boolean faceDown;
	private Set<String> playableCardIds = Collections.emptySet();

	public HandPanel(BattleCardCatalog catalog, CardArtProvider art)
	{
		this(catalog, art, Orientation.BOTTOM);
	}

	public HandPanel(BattleCardCatalog catalog, CardArtProvider art, Orientation orientation)
	{
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.art = Objects.requireNonNull(art, "art");
		this.orientation = Objects.requireNonNull(orientation, "orientation");
		setOpaque(false);
		setLayout(null);
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent event)
			{
				layoutTiles();
			}
		});
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				if (SwingUtilities.isRightMouseButton(event))
				{
					cancelListener.run();
				}
			}
		});
	}

	public void setHand(List<Card> hand)
	{
		Objects.requireNonNull(hand, "hand");
		clearTiles();
		faceDown = false;
		for (Card card : hand)
		{
			Optional<BattleCard> found = catalog.findById(card.getId());
			if (!found.isPresent())
			{
				log.warn("OSRS TCG Battles: hand card '{}' has no catalog entry, skipping tile", card.getId());
				continue;
			}
			addCardTile(found.get(), card.getId());
		}
		finishRebuild();
	}

	/** Receives only a count and constructs neutral backs without catalog lookup or art requests. */
	public void setFaceDownCount(int count)
	{
		clearTiles();
		faceDown = true;
		selectedCardId = null;
		for (int i = 0; i < Math.max(0, count); i++)
		{
			JComponent tile = new FaceDownTile();
			wireTile(tile, null, true);
		}
		finishRebuild();
	}

	public void setSelected(String cardId)
	{
		selectedCardId = cardId;
		applyCardStates();
		layoutTiles();
	}

	public void setPlayableCardIds(Set<String> cardIds)
	{
		playableCardIds = cardIds == null ? Collections.emptySet() : new HashSet<>(cardIds);
		applyCardStates();
	}

	private void applyCardStates()
	{
		for (int i = 0; i < tiles.size(); i++)
		{
			if (tiles.get(i) instanceof CardTile)
			{
				String id = cardIds.get(i);
				((CardTile) tiles.get(i)).setState(id != null && id.equals(selectedCardId)
					? CardTile.State.SELECTED : playableCardIds.contains(id)
						? CardTile.State.PLAYABLE : CardTile.State.DIMMED);
			}
		}
	}

	public void setCardClickListener(Consumer<String> listener)
	{
		cardClickListener = listener == null ? cardId -> { } : listener;
	}

	public void setCancelListener(Runnable listener)
	{
		cancelListener = listener == null ? () -> { } : listener;
	}

	@Override
	public void doLayout()
	{
		layoutTiles();
	}

	private void addCardTile(BattleCard card, String cardId)
	{
		wireTile(new CardTile(card, art), cardId, false);
	}

	private void wireTile(JComponent tile, String cardId, boolean hidden)
	{
		tiles.add(tile);
		cardIds.add(cardId);
		tile.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				if (SwingUtilities.isRightMouseButton(event))
				{
					cancelListener.run();
				}
				else if (!hidden)
				{
					cardClickListener.accept(cardId);
				}
			}

			@Override
			public void mouseEntered(MouseEvent event)
			{
				if (!hidden)
				{
					hoveredTile = tile;
					layoutTiles();
				}
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				if (hoveredTile == tile)
				{
					hoveredTile = null;
					layoutTiles();
				}
			}
		});
		add(tile, 0);
		restoreZOrder();
	}

	private void clearTiles()
	{
		removeAll();
		tiles.clear();
		cardIds.clear();
		hoveredTile = null;
	}

	private void finishRebuild()
	{
		layoutTiles();
		revalidate();
		repaint();
	}

	private void layoutTiles()
	{
		int count = tiles.size();
		if (count == 0 || getWidth() <= 0 || getHeight() <= 0)
		{
			return;
		}
		double middle = (count - 1) / 2.0;
		int maximumFan = (int) Math.round(middle * 1.5);
		int verticalInset = (orientation == Orientation.BOTTOM ? SELECTED_RAISE : 0) + maximumFan + 2;
		int verticalRoom = Math.max(24, getHeight() - verticalInset);
		int cardHeight = Math.min(BoardTheme.CARD_HEIGHT, verticalRoom);
		int cardWidth = Math.max(18, cardHeight * BoardTheme.CARD_WIDTH / BoardTheme.CARD_HEIGHT);
		int step = count == 1 ? 0 : Math.min(cardWidth + BoardTheme.ROW_GAP,
			Math.max(MIN_STEP, (getWidth() - cardWidth) / (count - 1)));
		int runWidth = cardWidth + step * (count - 1);
		int startX = Math.max(0, (getWidth() - runWidth) / 2);
		for (int i = 0; i < count; i++)
		{
			JComponent tile = tiles.get(i);
			int fan = (int) Math.round(Math.abs(i - middle) * 1.5);
			int y = orientation == Orientation.TOP ? fan : SELECTED_RAISE + fan;
			String cardId = cardIds.get(i);
			if (orientation == Orientation.BOTTOM)
			{
				if (cardId != null && cardId.equals(selectedCardId))
				{
					y -= SELECTED_RAISE;
				}
				else if (tile == hoveredTile)
				{
					y -= HOVER_RAISE;
				}
			}
			int x = startX + i * step;
			if (orientation == Orientation.BOTTOM && tile == hoveredTile)
			{
				int grow = Math.min(HOVER_GROW, Math.min(x, getWidth() - x - cardWidth));
				tile.setBounds(x - grow, Math.max(0, y - grow), cardWidth + grow * 2, cardHeight + grow * 2);
				setComponentZOrder(tile, 0);
			}
			else
			{
				tile.setBounds(x, y, cardWidth, cardHeight);
			}
		}
		if (hoveredTile == null)
		{
			restoreZOrder();
		}
		repaint();
	}

	private void restoreZOrder()
	{
		for (int i = 0; i < tiles.size(); i++)
		{
			setComponentZOrder(tiles.get(i), tiles.size() - 1 - i);
		}
	}

	int renderedCardCount()
	{
		return tiles.size();
	}

	boolean rendersOnlyFaceDownCards()
	{
		if (!faceDown)
		{
			return false;
		}
		for (JComponent tile : tiles)
		{
			if (!(tile instanceof FaceDownTile) || tile.getToolTipText() != null)
			{
				return false;
			}
		}
		return true;
	}

	Rectangle cardBounds(int index)
	{
		return new Rectangle(tiles.get(index).getBounds());
	}

	JComponent tileAt(int index)
	{
		return tiles.get(index);
	}

	private static final class FaceDownTile extends JComponent
	{
		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(39, 29, 21));
				g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
				g.setColor(BoardTheme.GOLD.darker());
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
				g.setColor(new Color(94, 68, 39));
				for (int x = -getHeight(); x < getWidth(); x += 12)
				{
					g.drawLine(x, getHeight(), x + getHeight(), 0);
				}
				int size = Math.max(10, Math.min(getWidth(), getHeight()) / 3);
				int cx = (getWidth() - size) / 2;
				int cy = (getHeight() - size) / 2;
				g.setColor(BoardTheme.GOLD.darker());
				g.fillOval(cx, cy, size, size);
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
