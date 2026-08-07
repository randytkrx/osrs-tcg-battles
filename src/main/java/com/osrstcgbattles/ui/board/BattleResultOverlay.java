package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.PlayerId;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingConstants;

/** Terminal match presentation which dims and blocks the board until it is closed. */
final class BattleResultOverlay extends JComponent
{
	private final JButton closeButton = new JButton("Close");
	private String title = "DRAW";
	private String finalLine = "The match ended in a draw.";

	BattleResultOverlay()
	{
		setLayout(null);
		setOpaque(false);
		setVisible(false);
		addMouseListener(new MouseAdapter() { });
		closeButton.setForeground(BoardTheme.TEXT);
		closeButton.setBackground(new Color(64, 48, 31));
		closeButton.setFocusPainted(false);
		closeButton.setHorizontalAlignment(SwingConstants.CENTER);
		closeButton.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BoardTheme.GOLD.darker()),
			BorderFactory.createEmptyBorder(4, 18, 4, 18)));
		add(closeButton);
	}

	void setCloseListener(Runnable listener)
	{
		for (java.awt.event.ActionListener existing : closeButton.getActionListeners())
		{
			closeButton.removeActionListener(existing);
		}
		Runnable action = listener == null ? () -> { } : listener;
		closeButton.addActionListener(event -> action.run());
	}

	void showResult(PlayerId localSeat, PlayerId winner, int turns)
	{
		title = resultTitle(localSeat, winner);
		if (winner == null)
		{
			finalLine = "The match ended in a draw after " + turns + " turns.";
		}
		else if (winner == localSeat)
		{
			finalLine = "You won the match in " + turns + " turns.";
		}
		else
		{
			finalLine = "Your opponent won in " + turns + " turns.";
		}
		setVisible(true);
		revalidate();
		repaint();
	}

	@Override
	public void doLayout()
	{
		Rectangle dialog = dialogBounds(getWidth(), getHeight());
		int buttonWidth = Math.max(96, Math.min(150, dialog.width / 3));
		int buttonHeight = Math.max(30, Math.min(40, dialog.height / 5));
		closeButton.setBounds(dialog.x + (dialog.width - buttonWidth) / 2,
			dialog.y + dialog.height - buttonHeight - Math.max(14, dialog.height / 10), buttonWidth, buttonHeight);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(5, 4, 3, 190));
			g.fillRect(0, 0, getWidth(), getHeight());
			Rectangle dialog = dialogBounds(getWidth(), getHeight());
			int arc = Math.max(16, dialog.height / 10);
			g.setColor(new Color(38, 28, 18, 250));
			g.fillRoundRect(dialog.x, dialog.y, dialog.width, dialog.height, arc, arc);
			g.setColor(BoardTheme.GOLD);
			g.setStroke(new BasicStroke(Math.max(2f, dialog.height / 100f)));
			g.drawRoundRect(dialog.x + 1, dialog.y + 1, dialog.width - 3, dialog.height - 3, arc, arc);

			float titleSize = Math.max(24f, Math.min(42f, dialog.height * .2f));
			g.setFont(getFont().deriveFont(Font.BOLD, titleSize));
			g.setColor("DEFEAT".equals(title) ? BoardTheme.HEALTH.brighter() : BoardTheme.GOLD);
			int titleY = dialog.y + Math.max(50, dialog.height / 3);
			g.drawString(title, dialog.x + (dialog.width - g.getFontMetrics().stringWidth(title)) / 2, titleY);

			g.setFont(getFont().deriveFont(Font.PLAIN, Math.max(12f, Math.min(17f, dialog.height * .075f))));
			g.setColor(BoardTheme.TEXT);
			int lineY = titleY + Math.max(30, dialog.height / 7);
			g.drawString(finalLine, dialog.x + (dialog.width - g.getFontMetrics().stringWidth(finalLine)) / 2, lineY);
		}
		finally
		{
			g.dispose();
		}
	}

	static String resultTitle(PlayerId localSeat, PlayerId winner)
	{
		if (winner == null) return "DRAW";
		return winner == localSeat ? "VICTORY" : "DEFEAT";
	}

	static Rectangle dialogBounds(int width, int height)
	{
		int safeWidth = Math.max(1, width);
		int safeHeight = Math.max(1, height);
		int dialogWidth = Math.max(300, Math.min(520, safeWidth * 3 / 5));
		int dialogHeight = Math.max(190, Math.min(260, safeHeight * 2 / 5));
		dialogWidth = Math.min(dialogWidth, safeWidth);
		dialogHeight = Math.min(dialogHeight, safeHeight);
		return new Rectangle((safeWidth - dialogWidth) / 2, (safeHeight - dialogHeight) / 2,
			dialogWidth, dialogHeight);
	}
}
