package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCard;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.JComponent;
import javax.swing.JList;

/** Consistent, privacy-safe card rules tooltips for battle and deck-building views. */
public final class BattleCardTooltips
{
	private BattleCardTooltips()
	{
	}

	public static void install(JComponent component, BattleCard card)
	{
		component.setToolTipText(format(card));
	}

	public static String format(BattleCard card)
	{
		Objects.requireNonNull(card, "card");
		String stats = card.getCategory().name().equals("UNIT")
			? card.getAttack() + " attack  |  " + card.getHealth() + " health  |  " : "";
		String tags = card.getTags().isEmpty() ? "" : "<br><font color='#9f9685'>"
			+ escapeHtml(String.join("  /  ", card.getTags())) + "</font>";
		return "<html><div style='width:260px;padding:4px'><b><font color='#f1d58a'>"
			+ escapeHtml(card.getDisplayName()) + "</font></b><br><font color='#b9ad98'>"
			+ escapeHtml(card.getRarity().name()) + " " + escapeHtml(card.getCategory().name())
			+ "  |  " + stats + card.getManaCost() + " mana</font><hr>"
			+ escapeHtml(card.getRulesText()) + tags + "</div></html>";
	}

	public static void install(JList<?> list, IntFunction<BattleCard> cardAtIndex)
	{
		Objects.requireNonNull(list, "list");
		Objects.requireNonNull(cardAtIndex, "cardAtIndex");
		list.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent event)
			{
				int index = list.locationToIndex(event.getPoint());
				Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
				BattleCard card = bounds != null && bounds.contains(event.getPoint()) ? cardAtIndex.apply(index) : null;
				list.setToolTipText(card == null ? null : format(card));
			}
		});
		list.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseExited(MouseEvent event)
			{
				list.setToolTipText(null);
			}
		});
	}

	static String escapeHtml(String value)
	{
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
			.replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}
}
