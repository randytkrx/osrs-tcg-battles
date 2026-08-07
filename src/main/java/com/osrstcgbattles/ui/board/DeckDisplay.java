package com.osrstcgbattles.ui.board;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/** Draw-pile stack/count and conditional fatigue indicator. */
public final class DeckDisplay extends JComponent
{
	private int count;
	private int fatigue;

	public void setValues(int count, int fatigue)
	{
		this.count = Math.max(0, count);
		this.fatigue = Math.max(0, fatigue);
		setToolTipText(this.count + " cards remain in the draw pile. "
			+ (this.fatigue > 0 ? "The next empty-deck draw deals at least " + (this.fatigue + 1)
				+ " fatigue damage." : "Drawing from an empty deck causes increasing fatigue damage."));
		repaint();
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int cardWidth = Math.min(38, getWidth() / 2);
			int cardHeight = Math.min(50, getHeight() - 8);
			int y = (getHeight() - cardHeight) / 2;
			for (int i = 2; i >= 0; i--)
			{
				g.setColor(new Color(54 + i * 7, 40 + i * 4, 27));
				g.fillRoundRect(3 + i * 2, y - i * 2, cardWidth, cardHeight, 5, 5);
				g.setColor(BoardTheme.GOLD.darker());
				g.drawRoundRect(3 + i * 2, y - i * 2, cardWidth, cardHeight, 5, 5);
			}
			g.setColor(BoardTheme.TEXT);
			g.setFont(getFont().deriveFont(Font.BOLD, 13f));
			g.drawString(Integer.toString(count), cardWidth + 12, getHeight() / 2);
			if (fatigue > 0)
			{
				g.setColor(BoardTheme.GLOW_TARGET);
				g.setFont(getFont().deriveFont(Font.BOLD, 10f));
				g.drawString("FATIGUE " + fatigue, cardWidth + 12, getHeight() / 2 + 14);
			}
		}
		finally
		{
			g.dispose();
		}
	}
}
