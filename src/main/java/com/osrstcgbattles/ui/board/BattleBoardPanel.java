package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.SwingConstants;

/** Cohesive layered OSRS battle surface containing all visual board elements. */
public final class BattleBoardPanel extends JLayeredPane
{
	private final BoardPanel boardPanel;
	private final HandPanel opponentHand;
	private final HandPanel localHand;
	private final BattleHeroPanel opponentHero = new BattleHeroPanel("Opponent");
	private final BattleHeroPanel localHero = new BattleHeroPanel("You");
	private final DeckDisplay opponentDeck = new DeckDisplay();
	private final DeckDisplay localDeck = new DeckDisplay();
	private final TurnButton endTurnButton = new TurnButton("END TURN");
	private final TurnButton keepHandButton = new TurnButton("KEEP HAND");
	private final JButton concedeButton = secondaryButton("Concede", new Color(145, 62, 55));
	private final StatusPlaque status = new StatusPlaque();
	private final TurnBanner turnBanner = new TurnBanner();
	private final BattleResultOverlay resultOverlay = new BattleResultOverlay();

	public BattleBoardPanel(BattleCardCatalog catalog, CardArtProvider art)
	{
		boardPanel = new BoardPanel(catalog, art);
		opponentHand = new HandPanel(catalog, art, HandPanel.Orientation.TOP);
		localHand = new HandPanel(catalog, art, HandPanel.Orientation.BOTTOM);
		setOpaque(true);
		setLayout(null);
		setPreferredSize(new Dimension(1100, 750));
		setMinimumSize(new Dimension(760, 560));

		add(boardPanel, DEFAULT_LAYER);
		add(opponentHand, PALETTE_LAYER);
		add(localHand, PALETTE_LAYER);
		add(opponentHero, PALETTE_LAYER);
		add(localHero, PALETTE_LAYER);
		add(opponentDeck, PALETTE_LAYER);
		add(localDeck, PALETTE_LAYER);
		add(status, MODAL_LAYER);
		add(endTurnButton, MODAL_LAYER);
		add(keepHandButton, MODAL_LAYER);
		add(concedeButton, MODAL_LAYER);
		add(turnBanner, POPUP_LAYER);
		add(resultOverlay, DRAG_LAYER);
	}

	@Override
	public void doLayout()
	{
		BattleBoardGeometry geometry = BattleBoardLayout.calculate(getWidth(), getHeight());
		opponentHand.setBounds(geometry.getOpponentHand());
		opponentHero.setBounds(geometry.getOpponentHero());
		boardPanel.setBounds(geometry.getBattlefield());
		localHero.setBounds(geometry.getLocalHero());
		localHand.setBounds(geometry.getLocalHand());
		opponentDeck.setBounds(geometry.getOpponentDeck());
		localDeck.setBounds(geometry.getLocalDeck());
		endTurnButton.setBounds(geometry.getTurnControl());
		keepHandButton.setBounds(geometry.getTurnControl());
		concedeButton.setBounds(geometry.getConcedeControl());
		status.setBounds(geometry.getStatus());
		turnBanner.setBounds(geometry.getTurnBanner());
		resultOverlay.setBounds(0, 0, getWidth(), getHeight());
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setPaint(new GradientPaint(0, 0, new Color(57, 48, 39), getWidth(), getHeight(),
				new Color(24, 19, 15)));
			g.fillRect(0, 0, getWidth(), getHeight());
			for (int y = 8; y < getHeight(); y += 18)
			{
				g.setColor(new Color(255, 255, 255, 6));
				g.drawLine(0, y, getWidth(), y + 4);
			}
			BattleBoardGeometry geometry = BattleBoardLayout.calculate(getWidth(), getHeight());
			Rectangle field = geometry.getBattlefield();
			g.setPaint(new GradientPaint(field.x, field.y, new Color(74, 54, 34, 225),
				field.x, field.y + field.height, new Color(43, 31, 22, 235)));
			g.fillRoundRect(field.x - 7, field.y - 7, field.width + 14, field.height + 14, 18, 18);
			g.setColor(new Color(112, 82, 48));
			g.setStroke(new BasicStroke(3f));
			g.drawRoundRect(field.x - 7, field.y - 7, field.width + 13, field.height + 13, 18, 18);
			int dividerY = field.y + field.height / 2;
			g.setColor(BoardTheme.GOLD.darker());
			g.setStroke(new BasicStroke(2f));
			g.drawLine(field.x + 16, dividerY, field.x + field.width - 16, dividerY);
		}
		finally
		{
			g.dispose();
		}
	}

	BoardPanel getBoardPanel() { return boardPanel; }
	HandPanel getOpponentHand() { return opponentHand; }
	HandPanel getLocalHand() { return localHand; }
	BattleHeroPanel getOpponentHero() { return opponentHero; }
	BattleHeroPanel getLocalHero() { return localHero; }
	DeckDisplay getOpponentDeck() { return opponentDeck; }
	DeckDisplay getLocalDeck() { return localDeck; }
	TurnButton getEndTurnButton() { return endTurnButton; }
	TurnButton getKeepHandButton() { return keepHandButton; }
	JButton getConcedeButton() { return concedeButton; }
	TurnBanner getTurnBanner() { return turnBanner; }
	BattleResultOverlay getResultOverlay() { return resultOverlay; }

	void setStatus(String turnNumber, String turn, String banner)
	{
		status.setValues(turnNumber, turn, banner);
	}

	private static JButton secondaryButton(String text, Color accent)
	{
		JButton button = new JButton(text);
		button.setForeground(BoardTheme.TEXT);
		button.setBackground(new Color(45, 35, 27));
		button.setFocusPainted(false);
		button.setHorizontalAlignment(SwingConstants.CENTER);
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent.darker()),
			BorderFactory.createEmptyBorder(2, 6, 2, 6)));
		return button;
	}

	private static final class StatusPlaque extends javax.swing.JComponent
	{
		private String turnNumber = " ";
		private String turn = " ";
		private String banner = " ";

		private void setValues(String turnNumber, String turn, String banner)
		{
			this.turnNumber = turnNumber;
			this.turn = turn;
			this.banner = banner;
			setToolTipText(banner == null || banner.trim().isEmpty() ? null : banner);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(39, 29, 19, 238));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				g.setColor(BoardTheme.GOLD.darker());
				g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				String primary = turnNumber + "  |  " + turn;
				g.setFont(getFont().deriveFont(Font.BOLD, 11f));
				g.setColor(BoardTheme.TEXT);
				g.drawString(primary, (getWidth() - g.getFontMetrics().stringWidth(primary)) / 2, 14);
				if (banner != null && !banner.trim().isEmpty())
				{
					g.setFont(getFont().deriveFont(Font.ITALIC, 10f));
					g.setColor(BoardTheme.TEXT_DIM);
					g.drawString(banner, Math.max(4, (getWidth() - g.getFontMetrics().stringWidth(banner)) / 2), 29);
				}
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
