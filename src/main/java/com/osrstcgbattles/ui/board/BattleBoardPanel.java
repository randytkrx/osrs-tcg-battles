package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLayeredPane;
import javax.swing.SwingConstants;

/** Cohesive layered OSRS battle surface containing all visual board elements. */
public final class BattleBoardPanel extends JLayeredPane
{
	private static final BufferedImage BOARD_BACKGROUND = loadBoardBackground();
	private static final Font FALLBACK_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);

	private final BattleCardCatalog catalog;
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
	private final JButton battleLogButton = secondaryButton("Battle Log", new Color(78, 113, 147));
	private final BattleLogPanel battleLog = new BattleLogPanel();
	private final StatusPlaque status = new StatusPlaque();
	private final TurnBanner turnBanner = new TurnBanner();
	private final TargetPrompt targetPrompt = new TargetPrompt();
	private final DragCardGhost dragGhost = new DragCardGhost();
	private final BattleResultOverlay resultOverlay = new BattleResultOverlay();

	public BattleBoardPanel(BattleCardCatalog catalog, CardArtProvider art)
	{
		this.catalog = catalog;
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
		add(battleLogButton, MODAL_LAYER);
		add(battleLog, POPUP_LAYER);
		add(turnBanner, POPUP_LAYER);
		add(targetPrompt, POPUP_LAYER);
		add(dragGhost, DRAG_LAYER);
		add(resultOverlay, DRAG_LAYER);
		targetPrompt.setVisible(false);
		dragGhost.setVisible(false);
		battleLog.setVisible(false);
		battleLogButton.addActionListener(event -> {
			battleLog.setVisible(!battleLog.isVisible());
			battleLogButton.setText(battleLog.isVisible() ? "Hide Log" : "Battle Log");
		});
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
		Rectangle turn = geometry.getTurnControl();
		Rectangle concede = geometry.getConcedeControl();
		int logButtonHeight = 26;
		int logButtonY = Math.max(turn.y + turn.height + 8, concede.y - logButtonHeight - 6);
		battleLogButton.setBounds(concede.x, logButtonY, concede.width, logButtonHeight);
		int logWidth = Math.min(230, Math.max(180, concede.width * 2));
		int availableHeight = Math.max(0, logButtonY - turn.y - turn.height - 14);
		int logHeight = Math.min(190, availableHeight);
		battleLog.setBounds(Math.max(4, concede.x + concede.width - logWidth), logButtonY - logHeight - 5,
			logWidth, logHeight);
		status.setBounds(geometry.getStatus());
		turnBanner.setBounds(geometry.getTurnBanner());
		Rectangle field = geometry.getBattlefield();
		Rectangle statusBounds = geometry.getStatus();
		int promptWidth = Math.min(440, Math.max(260, field.width - 48));
		targetPrompt.setBounds((getWidth() - promptWidth) / 2,
			Math.max(field.y + 8, statusBounds.y - 50), promptWidth, 40);
		resultOverlay.setBounds(0, 0, getWidth(), getHeight());
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			drawCover(g, BOARD_BACKGROUND, getWidth(), getHeight());
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setPaint(new GradientPaint(0, 0, new Color(18, 12, 7, 20), getWidth(), getHeight(),
				new Color(8, 5, 3, 85)));
			g.fillRect(0, 0, getWidth(), getHeight());
			BattleBoardGeometry geometry = BattleBoardLayout.calculate(getWidth(), getHeight());
			Rectangle field = geometry.getBattlefield();
			g.setPaint(new GradientPaint(field.x, field.y, new Color(31, 20, 12, 70),
				field.x, field.y + field.height, new Color(18, 11, 7, 105)));
			g.fillRoundRect(field.x - 7, field.y - 7, field.width + 14, field.height + 14, 18, 18);
			g.setColor(new Color(196, 142, 70, 150));
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

	private static BufferedImage loadBoardBackground()
	{
		try (InputStream stream = BattleBoardPanel.class.getResourceAsStream("/com/osrstcgbattles/board.png"))
		{
			if (stream == null)
			{
				throw new IllegalStateException("Missing OSRS TCG battle-board background");
			}
			BufferedImage image = javax.imageio.ImageIO.read(stream);
			if (image == null)
			{
				throw new IllegalStateException("Invalid OSRS TCG battle-board background");
			}
			return image;
		}
		catch (IOException exception)
		{
			throw new IllegalStateException("Unable to load OSRS TCG battle-board background", exception);
		}
	}

	private static void drawCover(Graphics2D graphics, BufferedImage image, int width, int height)
	{
		double sourceAspect = image.getWidth() / (double) image.getHeight();
		double targetAspect = width / (double) Math.max(1, height);
		int sourceX = 0;
		int sourceY = 0;
		int sourceWidth = image.getWidth();
		int sourceHeight = image.getHeight();
		if (sourceAspect > targetAspect)
		{
			sourceWidth = Math.max(1, (int) Math.round(sourceHeight * targetAspect));
			sourceX = (image.getWidth() - sourceWidth) / 2;
		}
		else
		{
			sourceHeight = Math.max(1, (int) Math.round(sourceWidth / targetAspect));
			sourceY = (image.getHeight() - sourceHeight) / 2;
		}
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.drawImage(image, 0, 0, width, height, sourceX, sourceY,
			sourceX + sourceWidth, sourceY + sourceHeight, null);
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
	JButton getBattleLogButton() { return battleLogButton; }
	BattleLogPanel getBattleLog() { return battleLog; }
	TurnBanner getTurnBanner() { return turnBanner; }
	BattleResultOverlay getResultOverlay() { return resultOverlay; }

	void setStatus(String turnNumber, String turn, String banner)
	{
		status.setValues(turnNumber, turn, banner);
	}

	void setTargetPrompt(String message)
	{
		targetPrompt.setMessage(message);
	}

	void showCardDrag(String cardId, Point point, boolean overBattlefield)
	{
		BattleCard card = catalog.findById(cardId).orElse(null);
		if (card == null || point == null)
		{
			dragGhost.setVisible(false);
			return;
		}
		dragGhost.setValues(card.getDisplayName(), overBattlefield);
		int width = 74;
		int height = 100;
		int x = Math.max(0, Math.min(getWidth() - width, point.x - width / 2));
		int y = Math.max(0, Math.min(getHeight() - height, point.y - height / 2));
		dragGhost.setBounds(x, y, width, height);
		dragGhost.setVisible(true);
		dragGhost.repaint();
	}

	void hideCardDrag()
	{
		dragGhost.setVisible(false);
	}

	void appendBattleLog(String entry)
	{
		battleLog.append(entry);
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

	private static final class TargetPrompt extends javax.swing.JComponent
	{
		private String message;

		private void setMessage(String message)
		{
			this.message = message;
			setVisible(message != null && !message.trim().isEmpty());
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(29, 20, 12, 242));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				g.setColor(BoardTheme.GOLD);
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
				Font font = getFont() == null ? FALLBACK_FONT : getFont();
				g.setFont(font.deriveFont(Font.BOLD, 12f));
				g.setColor(BoardTheme.TEXT);
				int x = Math.max(8, (getWidth() - g.getFontMetrics().stringWidth(message)) / 2);
				g.drawString(message, x, 25);
			}
			finally
			{
				g.dispose();
			}
		}
	}

	private static final class DragCardGhost extends javax.swing.JComponent
	{
		private String name = "Card";
		private boolean validDrop;

		private void setValues(String name, boolean validDrop)
		{
			this.name = name;
			this.validDrop = validDrop;
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setColor(new Color(30, 22, 15, 220));
				g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10);
				g.setColor(validDrop ? BoardTheme.GLOW_LEGAL : BoardTheme.TEXT_DIM);
				g.setStroke(new BasicStroke(3f));
				g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 10, 10);
				Font font = getFont() == null ? FALLBACK_FONT : getFont();
				g.setFont(font.deriveFont(Font.BOLD, 10f));
				g.setColor(BoardTheme.TEXT);
				String label = name.length() > 12 ? name.substring(0, 11) + "..." : name;
				g.drawString(label, Math.max(5, (getWidth() - g.getFontMetrics().stringWidth(label)) / 2),
					getHeight() / 2);
			}
			finally
			{
				g.dispose();
			}
		}
	}
}
