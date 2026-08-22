package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.catalog.Faction;
import com.osrstcgbattles.catalog.Rarity;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.PlayerId;
import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/** Every color, font, and metric the board uses. */
public final class BoardTheme
{
	public static final int CARD_WIDTH = 82;
	public static final int CARD_HEIGHT = 112;
	public static final int CARD_ARC = 8;
	public static final int ROW_GAP = 6;
	public static final int HAND_OVERLAP = 30;
	public static final int BAND_TOP_INSET = 16;
	public static final int BAND_BOTTOM_INSET = 4;

	/** One flat battlefield band per player. */
	public static final int BOARD_BAND_COUNT = PlayerId.values().length;

	/**
	 * The board's own required height: every band stacked at a full {@link #CARD_HEIGHT} with
	 * {@link #ROW_GAP} between them. This is what {@code BoardPanel#getPreferredSize()} reports, so
	 * a {@code JScrollPane} knows to scroll the board rather than let AWT shrink it below the point
	 * where a battlefield band is shorter than a card and tiles start overlapping.
	 */
	public static final int BOARD_HEIGHT = (CARD_HEIGHT + BAND_TOP_INSET + BAND_BOTTOM_INSET) * BOARD_BAND_COUNT
		+ ROW_GAP * (BOARD_BAND_COUNT - 1);

	/**
	 * The board's own required width: a fully-occupied battlefield
	 * ({@link DuelscapeEngine#BATTLEFIELD_LIMIT} units) laid out side by side. This uses the theoretical maximum
	 * rather than however many units happen to be on the board, so the reported size never depends on match
	 * state -- it can never need to grow or shrink out from under the player mid-match. A
	 * lightly-occupied battlefield is simply centered in the same space a full one would use.
	 */
	public static final int BOARD_WIDTH = CARD_WIDTH * DuelscapeEngine.BATTLEFIELD_LIMIT
		+ ROW_GAP * (DuelscapeEngine.BATTLEFIELD_LIMIT - 1);

	public static final Color BACKGROUND = new Color(18, 15, 12);
	public static final Color ROW_BAND = new Color(31, 26, 21);
	public static final Color ROW_BAND_LIGHT = new Color(48, 39, 30);
	public static final Color CARD_INNER = new Color(62, 51, 39);
	public static final Color CARD_PARCHMENT = new Color(218, 197, 155);
	public static final Color MANA = new Color(52, 122, 190);
	public static final Color ATTACK = new Color(196, 139, 48);
	public static final Color HEALTH = new Color(154, 52, 45);
	public static final Color TEXT = new Color(226, 214, 190);
	public static final Color TEXT_DIM = new Color(140, 130, 115);
	public static final Color GOLD = new Color(222, 180, 82);
	public static final Color GLOW_LEGAL = new Color(120, 220, 140);
	public static final Color GLOW_TARGET = new Color(235, 120, 110);
	public static final Color GLOW_SELECTED = new Color(240, 205, 110);

	private static final Map<Faction, Color> FACTION = new EnumMap<>(Faction.class);
	private static final Map<Rarity, Color> RARITY = new EnumMap<>(Rarity.class);

	static
	{
		FACTION.put(Faction.NEUTRAL, new Color(150, 146, 138));
		FACTION.put(Faction.ASGARNIA, new Color(120, 168, 224));
		FACTION.put(Faction.DESERT, new Color(224, 192, 112));
		FACTION.put(Faction.FREMENNIK, new Color(140, 200, 208));
		FACTION.put(Faction.KARAMJA, new Color(224, 140, 96));
		FACTION.put(Faction.KOUREND, new Color(160, 140, 216));
		FACTION.put(Faction.MISTHALIN, new Color(128, 192, 136));
		FACTION.put(Faction.MORYTANIA, new Color(168, 120, 168));
		FACTION.put(Faction.TIRANNWN, new Color(136, 208, 176));
		FACTION.put(Faction.WILDERNESS, new Color(208, 104, 104));

		RARITY.put(Rarity.COMMON, new Color(160, 156, 148));
		RARITY.put(Rarity.UNCOMMON, new Color(128, 192, 136));
		RARITY.put(Rarity.RARE, new Color(120, 168, 224));
		RARITY.put(Rarity.EPIC, new Color(176, 132, 216));
		RARITY.put(Rarity.LEGENDARY, new Color(232, 184, 96));
	}

	private BoardTheme()
	{
	}

	public static Color factionColor(Faction faction)
	{
		Color color = FACTION.get(faction);
		if (color == null)
		{
			throw new IllegalArgumentException("No color for faction " + faction);
		}
		return color;
	}

	public static Color rarityColor(Rarity rarity)
	{
		Color color = RARITY.get(rarity);
		if (color == null)
		{
			throw new IllegalArgumentException("No color for rarity " + rarity);
		}
		return color;
	}
}
