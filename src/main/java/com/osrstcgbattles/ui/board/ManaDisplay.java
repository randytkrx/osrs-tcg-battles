package com.osrstcgbattles.ui.board;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/** Compact mana pips with a numeric fallback at narrow sizes. */
public final class ManaDisplay extends JComponent
{
	private int mana;
	private int maximumMana;

	public ManaDisplay()
	{
		setPreferredSize(new Dimension(92, 18));
		setToolTipText("Mana available");
	}

	public void setMana(int mana, int maximumMana)
	{
		this.mana = Math.max(0, mana);
		this.maximumMana = Math.max(0, maximumMana);
		setToolTipText(this.mana + " mana available of " + this.maximumMana
			+ ". Playing a card spends its shown mana cost.");
		repaint();
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int pipSize = 8;
			int runWidth = maximumMana == 0 ? 0 : maximumMana * (pipSize + 2) - 2;
			if (maximumMana > 0 && runWidth <= getWidth() - 30)
			{
				int y = (getHeight() - pipSize) / 2;
				for (int i = 0; i < maximumMana; i++)
				{
					g.setColor(i < mana ? BoardTheme.MANA : new Color(37, 50, 62));
					g.fillOval(i * (pipSize + 2), y, pipSize, pipSize);
					g.setColor(BoardTheme.TEXT_DIM);
					g.drawOval(i * (pipSize + 2), y, pipSize, pipSize);
				}
			}
			g.setColor(BoardTheme.TEXT);
			g.setFont(getFont().deriveFont(Font.BOLD, 11f));
			String value = mana + "/" + maximumMana;
			g.drawString(value, getWidth() - g.getFontMetrics().stringWidth(value),
				(getHeight() + g.getFontMetrics().getAscent()) / 2 - 1);
		}
		finally
		{
			g.dispose();
		}
	}
}
