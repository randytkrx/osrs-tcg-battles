package com.osrstcgbattles.ui.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/** Large OSRS-styled primary turn action. */
public final class TurnButton extends JButton
{
	public TurnButton(String text)
	{
		super(text);
		setForeground(BoardTheme.TEXT);
		setFont(getFont().deriveFont(Font.BOLD, 14f));
		setContentAreaFilled(false);
		setBorderPainted(false);
		setFocusPainted(false);
		setToolTipText("Finish your turn");
	}

	public void setActionable(boolean actionable, String inactiveText)
	{
		setEnabled(actionable);
		setText(actionable ? "END TURN" : inactiveText);
		setToolTipText(actionable ? "End your turn and pass control to your opponent"
			: "You can end the turn when control returns to you");
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Color top = isEnabled() ? new Color(116, 78, 35) : new Color(57, 50, 42);
			Color bottom = isEnabled() ? new Color(65, 39, 18) : new Color(39, 35, 31);
			g.setPaint(new java.awt.GradientPaint(0, 0, top, 0, getHeight(), bottom));
			g.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 16, 16);
			g.setColor(isEnabled() ? BoardTheme.GOLD : BoardTheme.TEXT_DIM.darker());
			g.setStroke(new BasicStroke(2f));
			g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 16, 16);
		}
		finally
		{
			g.dispose();
		}
		super.paintComponent(graphics);
	}
}
