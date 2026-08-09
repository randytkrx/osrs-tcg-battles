package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.art.EmblemCardArt;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.CardAbility;
import com.osrstcgbattles.ui.BattleCardTooltips;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Objects;
import javax.swing.JComponent;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Draws one playing card: artwork, frame, mana cost, rarity gem, name, and combat stats.
 * Every color and metric comes from {@link BoardTheme}; nothing is hardcoded here.
 */
public class CardTile extends JComponent
{
	public enum State
	{
		IDLE,
		PLAYABLE,
		SELECTED,
		DIMMED,
		TARGETABLE,
		TARGETED
	}

	private static final float DIMMED_ALPHA = 0.45f;

	private final BattleCard card;

	private State state = State.IDLE;
	private BufferedImage art;
	private boolean faceDown;
	private boolean exhausted;
	private int currentAttack;
	private int currentHealth;
	private boolean shielded;
	private boolean stealthed;

	public CardTile(BattleCard card, CardArtProvider art)
	{
		this.card = Objects.requireNonNull(card, "card");
		this.currentAttack = card.getAttack();
		this.currentHealth = card.getHealth();
		this.shielded = hasAbility(AbilityType.SHIELD);
		this.stealthed = hasAbility(AbilityType.STEALTH);
		setOpaque(false);
		setPreferredSize(new Dimension(BoardTheme.CARD_WIDTH, BoardTheme.CARD_HEIGHT));
		BattleCardTooltips.install(this, card);
		Objects.requireNonNull(art, "art").request(card.getAzCardName(), this::acceptArt);
	}

	public void setState(State state)
	{
		this.state = Objects.requireNonNull(state, "state");
		repaint();
	}

	public State getState()
	{
		return state;
	}

	/** Board tiles show live combat stats; hand tiles retain the card's base stats. */
	public void setCurrentStats(int attack, int health)
	{
		this.currentAttack = attack;
		this.currentHealth = health;
		repaint();
	}

	public void setExhausted(boolean exhausted)
	{
		this.exhausted = exhausted;
		repaint();
	}

	public void setShielded(boolean shielded) { this.shielded = shielded; repaint(); }
	public void setStealthed(boolean stealthed) { this.stealthed = stealthed; repaint(); }

	public void setFaceDown(boolean faceDown)
	{
		this.faceDown = faceDown;
		setToolTipText(faceDown ? null : BattleCardTooltips.format(card));
		repaint();
	}

	private void acceptArt(BufferedImage image)
	{
		this.art = image;
		if (image instanceof AsyncBufferedImage)
		{
			((AsyncBufferedImage) image).onLoaded(this::repaint);
		}
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int width = getWidth();
		int height = getHeight();
		if (width == 0 || height == 0)
		{
			return;
		}

		Graphics2D graphics = (Graphics2D) g.create();
		try
		{
			Object previousAntialias = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			try
			{
				if (state == State.DIMMED || exhausted)
				{
					Composite previousComposite = graphics.getComposite();
					graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DIMMED_ALPHA));
					try
					{
						paintTile(graphics, width, height);
					}
					finally
					{
						graphics.setComposite(previousComposite);
					}
					paintStateBorder(graphics, width, height, faceDown
						? BoardTheme.TEXT_DIM : BoardTheme.factionColor(card.getFaction()));
				}
				else
				{
					paintTile(graphics, width, height);
				}
			}
			finally
			{
				if (previousAntialias != null)
				{
					graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousAntialias);
				}
			}
		}
		finally
		{
			graphics.dispose();
		}
	}

	private void paintTile(Graphics2D graphics, int width, int height)
	{
		if (faceDown)
		{
			// Faction-neutral: revealing the faction color would leak which faction a hidden
			// opponent card belongs to, narrowing down its identity.
			paintFaceDownFrame(graphics, width, height);
			paintFaceDownPattern(graphics, width, height);
			paintStateBorder(graphics, width, height, BoardTheme.TEXT_DIM);
			return;
		}

		Color faction = BoardTheme.factionColor(card.getFaction());

		paintFrame(graphics, width, height, faction);
		paintArt(graphics, width, height, faction);
		paintNameStrip(graphics, width, height);
		paintManaPip(graphics, width, height);
		paintRarityGem(graphics, width, height);
		paintStatsBadge(graphics, width, height);
		paintKeywordBadges(graphics, width);
		paintStateBorder(graphics, width, height, faction);
	}

	private void paintKeywordBadges(Graphics2D graphics, int width)
	{
		java.util.List<String> labels = new java.util.ArrayList<>();
		if (shielded) labels.add("S");
		if (hasAbility(AbilityType.LIFESTEAL)) labels.add("L");
		if (hasAbility(AbilityType.POISONOUS)) labels.add("P");
		if (hasAbility(AbilityType.RUSH)) labels.add("R");
		if (hasAbility(AbilityType.TAUNT)) labels.add("T");
		if (stealthed) labels.add("ST");
		if (hasAbility(AbilityType.NEX_ASCENSION)) labels.add("N");
		if (hasDeathrattle()) labels.add("D");
		int x = width - 15;
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 8f));
		for (String label : labels)
		{
			int badgeWidth = "ST".equals(label) ? 18 : 13;
			x -= badgeWidth;
			graphics.setColor(new Color(24, 19, 14, 225));
			graphics.fillRoundRect(x, 4, badgeWidth, 13, 6, 6);
			graphics.setColor(BoardTheme.GOLD);
			graphics.drawRoundRect(x, 4, badgeWidth, 13, 6, 6);
			graphics.drawString(label, x + (badgeWidth - graphics.getFontMetrics().stringWidth(label)) / 2, 14);
			x -= 2;
		}
	}

	private boolean hasAbility(AbilityType type)
	{
		for (CardAbility ability : card.getAbilities()) if (ability.getType() == type) return true;
		return false;
	}

	private boolean hasDeathrattle()
	{
		for (CardAbility ability : card.getAbilities())
			if (ability.getType().name().startsWith("DEATHRATTLE_")) return true;
		return false;
	}

	private void paintFrame(Graphics2D graphics, int width, int height, Color faction)
	{
		graphics.setColor(new Color(0, 0, 0, 110));
		graphics.fillRoundRect(3, 4, width - 4, height - 4, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
		graphics.setPaint(new GradientPaint(0, 0, faction.brighter(), width, height, faction.darker().darker()));
		graphics.fillRoundRect(0, 0, width - 2, height - 2, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
		graphics.setColor(BoardTheme.CARD_INNER);
		graphics.fillRoundRect(3, 3, width - 8, height - 8, BoardTheme.CARD_ARC - 2, BoardTheme.CARD_ARC - 2);
		graphics.setColor(new Color(255, 235, 175, 90));
		graphics.drawRoundRect(1, 1, width - 4, height - 4, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
	}

	private void paintArt(Graphics2D graphics, int width, int height, Color faction)
	{
		int margin = Math.max(2, width / 16);
		int artX = margin;
		int artY = margin;
		int artWidth = width - margin * 2;
		int artHeight = height - margin * 2 - height / 5;
		if (artWidth <= 0 || artHeight <= 0)
		{
			return;
		}

		Shape previousClip = graphics.getClip();
		graphics.clip(new RoundRectangle2D.Float(artX, artY, artWidth, artHeight, 7, 7));
		try
		{
			if (art != null)
			{
				graphics.drawImage(art, artX, artY, artWidth, artHeight, null);
			}
			else
			{
				EmblemCardArt.draw(graphics, artX, artY, artWidth, artHeight, faction, card.getDisplayName());
			}
			graphics.setPaint(new GradientPaint(0, artY + artHeight / 2, new Color(0, 0, 0, 0),
				0, artY + artHeight, new Color(8, 6, 4, 190)));
			graphics.fillRect(artX, artY, artWidth, artHeight);
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	private void paintNameStrip(Graphics2D graphics, int width, int height)
	{
		int stripHeight = Math.max(12, height / 6);
		int stripY = height - stripHeight;

		graphics.setPaint(new GradientPaint(0, stripY, BoardTheme.CARD_INNER, 0, height, BoardTheme.BACKGROUND));
		graphics.fillRect(0, stripY, width, stripHeight);

		graphics.setColor(BoardTheme.TEXT);
		Font previousFont = graphics.getFont();
		graphics.setFont(previousFont.deriveFont(Font.BOLD, Math.max(9f, stripHeight * 0.57f)));
		try
		{
			FontMetrics metrics = graphics.getFontMetrics();
			String name = elideToFit(metrics, card.getDisplayName(), width - 6);
			int textX = Math.max(3, (width - metrics.stringWidth(name)) / 2);
			int textY = stripY + (stripHeight + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.drawString(name, textX, textY);
		}
		finally
		{
			graphics.setFont(previousFont);
		}
	}

	private static String elideToFit(FontMetrics metrics, String text, int maxWidth)
	{
		if (text == null)
		{
			return "";
		}
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		String ellipsis = "…";
		StringBuilder builder = new StringBuilder(text);
		while (builder.length() > 0 && metrics.stringWidth(builder.toString() + ellipsis) > maxWidth)
		{
			builder.deleteCharAt(builder.length() - 1);
		}
		return builder.toString() + ellipsis;
	}

	private void paintManaPip(Graphics2D graphics, int width, int height)
	{
		int diameter = Math.max(14, width / 5);
		int x = 2;
		int y = 2;

		graphics.setColor(new Color(7, 30, 55));
		graphics.fillOval(x, y, diameter, diameter);
		graphics.setColor(BoardTheme.MANA.brighter());
		graphics.drawOval(x, y, diameter, diameter);

		String cost = String.valueOf(card.getManaCost());
		Font previousFont = graphics.getFont();
		graphics.setFont(previousFont.deriveFont(Font.BOLD, Math.max(9f, diameter * 0.55f)));
		try
		{
			FontMetrics metrics = graphics.getFontMetrics();
			int textX = x + (diameter - metrics.stringWidth(cost)) / 2;
			int textY = y + (diameter + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.drawString(cost, textX, textY);
		}
		finally
		{
			graphics.setFont(previousFont);
		}
	}

	private void paintRarityGem(Graphics2D graphics, int width, int height)
	{
		int diameter = Math.max(10, width / 7);
		int x = width - diameter - 2;
		int y = 2;

		graphics.setColor(BoardTheme.rarityColor(card.getRarity()));
		int[] xs = {x + diameter / 2, x + diameter, x + diameter / 2, x};
		int[] ys = {y, y + diameter / 2, y + diameter, y + diameter / 2};
		graphics.fillPolygon(xs, ys, 4);
		graphics.setColor(BoardTheme.BACKGROUND);
		graphics.drawPolygon(xs, ys, 4);
	}

	private void paintStatsBadge(Graphics2D graphics, int width, int height)
	{
		int diameter = Math.max(14, width / 5);
		int stripHeight = Math.max(12, height / 6);
		int x = 3;
		int y = height - stripHeight - diameter + Math.max(2, diameter / 3);
		int healthX = width - diameter - 3;

		paintStatBadge(graphics, x, y, diameter, BoardTheme.ATTACK, currentAttack);
		paintStatBadge(graphics, healthX, y, diameter, BoardTheme.HEALTH, currentHealth);
	}

	private void paintStatBadge(Graphics2D graphics, int x, int y, int diameter, Color color, int value)
	{
		graphics.setColor(new Color(8, 7, 5));
		graphics.fillOval(x - 1, y - 1, diameter + 2, diameter + 2);
		graphics.setColor(color);
		graphics.fillOval(x, y, diameter, diameter);
		graphics.setColor(BoardTheme.TEXT);
		graphics.drawOval(x, y, diameter, diameter);

		String stats = String.valueOf(value);
		Font previousFont = graphics.getFont();
		graphics.setFont(previousFont.deriveFont(Font.BOLD, Math.max(9f, diameter * 0.55f)));
		try
		{
			FontMetrics metrics = graphics.getFontMetrics();
			int textX = x + (diameter - metrics.stringWidth(stats)) / 2;
			int textY = y + (diameter + metrics.getAscent() - metrics.getDescent()) / 2;
			graphics.drawString(stats, textX, textY);
		}
		finally
		{
			graphics.setFont(previousFont);
		}
	}

	private void paintFaceDownFrame(Graphics2D graphics, int width, int height)
	{
		graphics.setPaint(new GradientPaint(0, 0, BoardTheme.ROW_BAND_LIGHT, width, height, BoardTheme.BACKGROUND));
		graphics.fillRoundRect(0, 0, width - 1, height - 1, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
		graphics.setColor(BoardTheme.TEXT_DIM);
		graphics.drawRoundRect(0, 0, width - 1, height - 1, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
	}

	private void paintFaceDownPattern(Graphics2D graphics, int width, int height)
	{
		Color backBase = BoardTheme.BACKGROUND;
		Shape previousClip = graphics.getClip();
		Shape body = new RoundRectangle2D.Float(1, 1, width - 2, height - 2, BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
		graphics.clip(body);
		try
		{
			graphics.setColor(backBase);
			graphics.fillRect(0, 0, width, height);

			graphics.setColor(BoardTheme.TEXT_DIM);
			int step = Math.max(6, width / 8);
			for (int diagonal = -height; diagonal < width; diagonal += step)
			{
				graphics.drawLine(diagonal, height, diagonal + height, 0);
			}
		}
		finally
		{
			graphics.setClip(previousClip);
		}
	}

	/** {@code idleColor} is the faction color for a face-up tile, or a neutral color face-down. */
	private void paintStateBorder(Graphics2D graphics, int width, int height, Color idleColor)
	{
		switch (state)
		{
			case SELECTED:
				drawBorder(graphics, width, height, BoardTheme.GLOW_SELECTED, 2);
				break;
			case TARGETED:
				drawBorder(graphics, width, height, BoardTheme.GLOW_TARGET, 2);
				break;
			case PLAYABLE:
				drawBorder(graphics, width, height, BoardTheme.GLOW_LEGAL, 1);
				break;
			case TARGETABLE:
				drawBorder(graphics, width, height, BoardTheme.GLOW_TARGET, 1);
				break;
			case IDLE:
			case DIMMED:
			default:
				drawBorder(graphics, width, height, idleColor.darker().darker(), 1);
				break;
		}
	}

	private void drawBorder(Graphics2D graphics, int width, int height, Color color, int thickness)
	{
		Stroke previousStroke = graphics.getStroke();
		try
		{
			graphics.setStroke(new BasicStroke(thickness));
			graphics.setColor(color);
			int inset = thickness / 2;
			graphics.drawRoundRect(inset, inset, width - 1 - inset * 2, height - 1 - inset * 2,
				BoardTheme.CARD_ARC, BoardTheme.CARD_ARC);
		}
		finally
		{
			graphics.setStroke(previousStroke);
		}
	}
}
