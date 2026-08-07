package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.Timer;

/** A brief, mouse-transparent announcement when play passes between seats. */
final class TurnBanner extends JComponent
{
	private static final int DISPLAY_MILLIS = 1400;
	private final Timer hideTimer;
	private String text = "";

	TurnBanner()
	{
		setOpaque(false);
		setVisible(false);
		hideTimer = new Timer(DISPLAY_MILLIS, event -> setVisible(false));
		hideTimer.setRepeats(false);
	}

	void showTurn(boolean localTurn)
	{
		text = localTurn ? "YOUR TURN" : "OPPONENT'S TURN";
		hideTimer.restart();
		setVisible(true);
		repaint();
	}

	void hideBanner()
	{
		hideTimer.stop();
		setVisible(false);
	}

	@Override
	public boolean contains(int x, int y)
	{
		return false;
	}

	@Override
	public void removeNotify()
	{
		hideTimer.stop();
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int arc = Math.max(12, getHeight() / 3);
			g.setColor(new Color(25, 18, 11, 242));
			g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
			g.setColor(BoardTheme.GOLD);
			g.setStroke(new BasicStroke(Math.max(2f, getHeight() / 28f)));
			g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, arc, arc);
			float fontSize = Math.max(18f, Math.min(30f, getHeight() * .38f));
			g.setFont(getFont().deriveFont(Font.BOLD, fontSize));
			g.setColor(BoardTheme.TEXT);
			int x = (getWidth() - g.getFontMetrics().stringWidth(text)) / 2;
			int y = (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent();
			g.drawString(text, Math.max(8, x), y);
		}
		finally
		{
			g.dispose();
		}
	}

	static boolean shouldShow(boolean hasPrevious, MatchStatus previousStatus, MatchPhase previousPhase,
		PlayerId previousActive, MatchStatus nextStatus, MatchPhase nextPhase, PlayerId nextActive)
	{
		if (!hasPrevious || nextStatus != MatchStatus.ACTIVE || nextPhase != MatchPhase.PLAY
			|| nextActive == null)
		{
			return false;
		}
		return previousStatus != nextStatus || previousPhase != nextPhase || previousActive != nextActive;
	}
}
