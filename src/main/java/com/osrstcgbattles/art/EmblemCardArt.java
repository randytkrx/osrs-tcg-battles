package com.osrstcgbattles.art;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import java.util.Objects;

/**
 * Placeholder art: a crest in the card's faction color with the card's initials.
 * Takes the color as a parameter rather than a Faction so this package never depends
 * on ui.board, which already depends on this one.
 */
public final class EmblemCardArt
{
	private EmblemCardArt()
	{
	}

	public static void draw(Graphics2D graphics, int x, int y, int width, int height,
		Color base, String displayName)
	{
		Objects.requireNonNull(base, "base");
		Object previous = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		try
		{
			graphics.setColor(base.darker().darker());
			graphics.fillRoundRect(x, y, width, height, 8, 8);
			graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 90));
			graphics.fillOval(x + width / 6, y + height / 6, width * 2 / 3, height * 2 / 3);
			graphics.setColor(base);
			graphics.drawOval(x + width / 6, y + height / 6, width * 2 / 3, height * 2 / 3);

			String initials = initials(displayName);
			graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, Math.max(11f, height / 4f)));
			FontMetrics metrics = graphics.getFontMetrics();
			int textX = x + (width - metrics.stringWidth(initials)) / 2;
			int textY = y + (height + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.drawString(initials, textX, textY);
		}
		finally
		{
			if (previous != null)
			{
				graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
			}
		}
	}

	private static String initials(String displayName)
	{
		if (displayName == null || displayName.trim().isEmpty())
		{
			return "?";
		}
		String[] words = displayName.trim().split("\\s+");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < words.length && out.length() < 2; i++)
		{
			if (!words[i].isEmpty())
			{
				out.append(Character.toUpperCase(words[i].charAt(0)));
			}
		}
		return out.toString().toUpperCase(Locale.ROOT);
	}
}
