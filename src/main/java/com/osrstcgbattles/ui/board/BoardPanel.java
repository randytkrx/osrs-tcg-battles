package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.BoardUnit;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * The opponent and local player's flat battlefield bands, each containing at most seven units. Board
 * state is rebuilt into fresh {@link CardTile}s on every {@link #setState} call rather than
 * diffed against the previous layout.
 *
 * Card bounds are adapted by {@link BoardLayout}, allowing both rows to remain centered without a
 * scroll pane when the battle window is narrowed.
 */
@Slf4j
public class BoardPanel extends JPanel
{
	private final BattleCardCatalog catalog;
	private final CardArtProvider art;

	private final Map<PlayerId, JPanel> bands = new HashMap<>();
	private final Map<String, CardTile> tilesByInstanceId = new HashMap<>();

	private Set<String> selectableAttackerIds = Collections.emptySet();
	private Set<String> targetableInstanceIds = Collections.emptySet();
	private String selectedAttackerId;
	private boolean interactionPending;
	private Consumer<String> unitClickListener = instanceId -> { };
	private Runnable cancelListener = () -> { };
	private PlayerId viewSeat;

	public BoardPanel(BattleCardCatalog catalog, CardArtProvider art)
	{
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.art = Objects.requireNonNull(art, "art");
		setOpaque(false);
		setLayout(new GridLayout(BoardTheme.BOARD_BAND_COUNT, 1, 0, BoardTheme.ROW_GAP));

		// Right-click cancels a pending selection from anywhere on the board, including empty space
		// between/around the battlefield bands; addBand() wires the same gesture onto each band and
		// tile so it also fires when the click lands on one of those instead of this panel's background.
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					cancelListener.run();
				}
			}
		});
	}

	@Override
	public Dimension getPreferredSize()
	{
		if (isPreferredSizeSet())
		{
			return super.getPreferredSize();
		}
		return new Dimension(BoardTheme.BOARD_WIDTH, BoardTheme.BOARD_HEIGHT);
	}

	public void setState(MatchState state, PlayerId localSeat)
	{
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(localSeat, "localSeat");
		PlayerId opponentSeat = localSeat.opponent();
		viewSeat = localSeat;

		removeAll();
		bands.clear();
		tilesByInstanceId.clear();

		// A rebuild throws away every tile from the previous state, so any highlighting the
		// caller set for that state (an attacker or a unit that can be targeted) is
		// now meaningless: the tiles it referred to no longer exist. Dropping both here means a
		// caller that forgets to re-issue the highlighting after a state change gets a
		// board with no stale highlights, rather than a card that silently looks playable when
		// the new turn no longer allows it.
		selectableAttackerIds = Collections.emptySet();
		targetableInstanceIds = Collections.emptySet();
		selectedAttackerId = null;
		interactionPending = false;

		addBand(state, opponentSeat);
		addBand(state, localSeat);

		applyTargetableHighlighting();
		revalidate();
		repaint();
	}

	public void setSelectableAttackers(List<BoardUnit> units)
	{
		this.selectableAttackerIds = instanceIds(units);
		applyTargetableHighlighting();
	}

	public void setTargetable(List<BoardUnit> units)
	{
		this.targetableInstanceIds = instanceIds(units);
		applyTargetableHighlighting();
	}

	public void setSelectedAttacker(String instanceId)
	{
		this.selectedAttackerId = instanceId;
		applyTargetableHighlighting();
	}

	public void setInteractionPending(boolean pending)
	{
		interactionPending = pending;
		applyTargetableHighlighting();
	}

	public void setUnitClickListener(Consumer<String> listener)
	{
		this.unitClickListener = listener == null ? instanceId -> { } : listener;
	}

	/** Right-click anywhere on the board -- a band, a tile, or empty space -- cancels a selection. */
	public void setCancelListener(Runnable listener)
	{
		this.cancelListener = listener == null ? () -> { } : listener;
	}

	private void addBand(MatchState state, PlayerId owner)
	{
		JPanel band = new JPanel(new BoardLayout(BoardTheme.ROW_GAP));
		band.setOpaque(false);
		band.setBorder(BorderFactory.createEmptyBorder(BoardTheme.BAND_TOP_INSET, 6,
			BoardTheme.BAND_BOTTOM_INSET, 6));

		for (BoardUnit unit : state.getBoard().getUnits(owner))
		{
			Optional<BattleCard> found = catalog.findById(unit.getDefinition().getId());
			if (!found.isPresent())
			{
				log.warn("OSRS TCG Battles: board unit '{}' has no catalog entry, skipping tile",
					unit.getDefinition().getId());
				continue;
			}
			CardTile tile = new CardTile(found.get(), art);
			tile.setCurrentStats(unit.getCurrentAttack(), unit.getCurrentHealth());
			tile.setShielded(unit.isShielded());
			tile.setStealthed(unit.isStealthed());
			// Enemy readiness does not affect whether that unit is a legal attack target.
			tile.setExhausted(owner == viewSeat && !unit.isReady());
			String instanceId = unit.getInstanceId();
			tilesByInstanceId.put(instanceId, tile);
			// Attached once, for the tile's whole lifetime, rather than added/removed on every
			// highlight refresh. Every tile remains clickable so ready allies can be selected and
			// highlighted enemies can be attacked; right-click always cancels either selection.
			tile.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (SwingUtilities.isRightMouseButton(e))
					{
						cancelListener.run();
					}
					else
					{
						unitClickListener.accept(instanceId);
					}
				}
			});
			band.add(tile);
		}

		band.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					cancelListener.run();
				}
			}
		});

		bands.put(owner, band);
		add(band);
	}

	private static Set<String> instanceIds(List<BoardUnit> units)
	{
		Set<String> ids = new HashSet<String>();
		if (units != null)
		{
			for (BoardUnit unit : units)
			{
				ids.add(unit.getInstanceId());
			}
		}
		return ids;
	}

	private void applyTargetableHighlighting()
	{
		for (Map.Entry<String, CardTile> entry : tilesByInstanceId.entrySet())
		{
			String instanceId = entry.getKey();
			CardTile tile = entry.getValue();
			if (targetableInstanceIds.contains(instanceId))
			{
				tile.setState(CardTile.State.TARGETABLE);
			}
			else if (instanceId.equals(selectedAttackerId))
			{
				tile.setState(CardTile.State.SELECTED);
			}
			else if (selectableAttackerIds.contains(instanceId))
			{
				tile.setState(CardTile.State.PLAYABLE);
			}
			else if (interactionPending)
			{
				tile.setState(CardTile.State.DIMMED);
			}
			else
			{
				tile.setState(CardTile.State.IDLE);
			}
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D graphics = (Graphics2D) g;
		for (PlayerId player : PlayerId.values())
		{
			JPanel band = bands.get(player);
			if (band == null)
			{
				continue;
			}
			Rectangle bounds = band.getBounds();
			graphics.setPaint(new GradientPaint(bounds.x, bounds.y, BoardTheme.ROW_BAND_LIGHT,
				bounds.x, bounds.y + bounds.height, BoardTheme.ROW_BAND));
			graphics.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 14, 14);
			graphics.setColor(player == viewSeat ? BoardTheme.GOLD : BoardTheme.TEXT_DIM);
			graphics.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, 14, 14);
			graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 10f));
			graphics.drawString(player == viewSeat ? "YOUR BATTLEFIELD" : "OPPONENT",
				bounds.x + 10, bounds.y + 15);
		}

		super.paintComponent(g);
	}

	/**
	 * Package-private test seam: a tile's bounds in this panel's own coordinate space (the
	 * band's bounds plus the tile's bounds within the band). Not part of the public API.
	 */
	Rectangle tileBoundsOnBoard(String instanceId)
	{
		CardTile tile = tilesByInstanceId.get(instanceId);
		if (tile == null)
		{
			return null;
		}
		Rectangle bandBounds = ((JPanel) tile.getParent()).getBounds();
		Rectangle tileBounds = tile.getBounds();
		return new Rectangle(bandBounds.x + tileBounds.x, bandBounds.y + tileBounds.y,
			tileBounds.width, tileBounds.height);
	}

	/** Package-private test seam: a battlefield band's bounds in this panel's own coordinate space. */
	Rectangle bandBounds(PlayerId player)
	{
		JPanel band = bands.get(player);
		return band == null ? null : band.getBounds();
	}

	/** Package-private test seam: the tile for a board unit, so tests can read its render state. */
	CardTile tileFor(String instanceId)
	{
		return tilesByInstanceId.get(instanceId);
	}
}
