package com.osrstcgbattles.ui.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.FontMetrics;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/** Hero identity/stats presentation, ready for a later supplied avatar. */
public final class BattleHeroPanel extends JPanel
{
	private final ManaDisplay manaDisplay = new ManaDisplay();
	private String identity;
	private Image avatar;
	private int health;
	private int mana;
	private int maximumMana;
	private boolean targetable;
	private boolean hovered;
	private boolean inactive;
	private Runnable clickListener = () -> { };

	public BattleHeroPanel(String identity)
	{
		setLayout(null);
		setOpaque(false);
		setIdentity(identity, null);
		add(manaDisplay);
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent event)
			{
				if (targetable)
				{
					hovered = true;
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent event)
			{
				hovered = false;
				repaint();
			}

			@Override
			public void mouseClicked(MouseEvent event)
			{
				if (targetable && event.getButton() == MouseEvent.BUTTON1)
				{
					clickListener.run();
				}
			}
		};
		addMouseListener(mouse);
		manaDisplay.addMouseListener(mouse);
	}

	public void setIdentity(String identity, Image avatar)
	{
		this.identity = identity == null || identity.trim().isEmpty() ? "Player" : identity.trim();
		this.avatar = avatar;
		refreshTooltip();
		repaint();
	}

	public void setStats(int health, int mana, int maximumMana)
	{
		this.health = health;
		this.mana = mana;
		this.maximumMana = maximumMana;
		manaDisplay.setMana(mana, maximumMana);
		refreshTooltip();
		repaint();
	}

	public void setTargetable(boolean targetable)
	{
		this.targetable = targetable;
		hovered = false;
		setCursor(Cursor.getPredefinedCursor(targetable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		manaDisplay.setCursor(getCursor());
		refreshTooltip();
		repaint();
	}

	public void setInactive(boolean inactive)
	{
		this.inactive = inactive;
		repaint();
	}

	public void setClickListener(Runnable listener)
	{
		clickListener = listener == null ? () -> { } : listener;
	}

	private void refreshTooltip()
	{
		setToolTipText(identity + ": " + health + " health, " + mana + " of " + maximumMana + " mana"
			+ (targetable ? ". Click to attack this hero." : ""));
	}

	@Override
	public void doLayout()
	{
		int emblem = Math.max(34, getHeight() - 12);
		manaDisplay.setBounds(emblem + 18, getHeight() - 25, Math.max(50, getWidth() - emblem - 26), 18);
	}

	@Override
	protected void paintComponent(Graphics graphics)
	{
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(health <= 0 ? new Color(48, 25, 22, 235)
				: health <= 5 ? new Color(51, 31, 22, 235) : new Color(28, 22, 16, 235));
			g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
			g.setColor(targetable ? (hovered ? BoardTheme.GLOW_TARGET.brighter() : BoardTheme.GLOW_TARGET)
				: health <= 0 ? BoardTheme.HEALTH.darker() : BoardTheme.GOLD.darker());
			g.setStroke(new BasicStroke(targetable ? (hovered ? 4f : 3f) : 2f));
			g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 14, 14);
			int emblem = Math.max(34, getHeight() - 12);
			int y = (getHeight() - emblem) / 2;
			if (avatar != null)
			{
				g.drawImage(avatar, 6, y, emblem, emblem, this);
			}
			else
			{
				int seed = identity.toUpperCase().hashCode();
				g.setColor(new Color(70 + Math.floorMod(seed, 45), 55 + Math.floorMod(seed / 7, 40), 35));
				g.fillOval(6, y, emblem, emblem);
				g.setColor(BoardTheme.GOLD);
				g.drawOval(6, y, emblem, emblem);
				g.setFont(getFont().deriveFont(Font.BOLD, Math.max(14f, emblem * .38f)));
				String initials = initials(identity);
				g.drawString(initials, 6 + (emblem - g.getFontMetrics().stringWidth(initials)) / 2,
					y + (emblem + g.getFontMetrics().getAscent()) / 2 - 2);
			}
			int textX = emblem + 18;
			g.setColor(BoardTheme.TEXT);
			g.setFont(getFont().deriveFont(Font.BOLD, Math.max(11f, Math.min(14f, getHeight() * .2f))));
			int healthSize = Math.max(22, Math.min(28, getHeight() - 20));
			int healthX = getWidth() - healthSize - 22;
			String shownIdentity = fitText(g.getFontMetrics(), identity, Math.max(0, healthX - textX - 6));
			g.drawString(shownIdentity, textX, Math.max(g.getFontMetrics().getAscent() + 4, getHeight() / 3));
			g.setColor(BoardTheme.HEALTH);
			g.fillOval(healthX, 7, healthSize, healthSize);
			g.setColor(Color.WHITE);
			String value = Integer.toString(health);
			g.setFont(getFont().deriveFont(Font.BOLD, Math.max(11f, healthSize * .45f)));
			g.drawString(value, healthX + (healthSize - g.getFontMetrics().stringWidth(value)) / 2,
				7 + (healthSize - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent());
			if (health <= 0)
			{
				g.setColor(new Color(8, 7, 5, 155));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
				g.setColor(BoardTheme.HEALTH.brighter());
				g.drawString("DEFEATED", textX, getHeight() - 9);
			}
			else if (inactive && !targetable)
			{
				g.setColor(new Color(8, 7, 5, 75));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private static String initials(String value)
	{
		String[] words = value.trim().split("\\s+");
		String first = words[0].substring(0, 1);
		return (first + (words.length > 1 ? words[words.length - 1].substring(0, 1) : "")).toUpperCase();
	}

	private static String fitText(FontMetrics metrics, String value, int width)
	{
		if (metrics.stringWidth(value) <= width) return value;
		String suffix = "...";
		int available = width - metrics.stringWidth(suffix);
		if (available <= 0) return "";
		int end = value.length();
		while (end > 0 && metrics.stringWidth(value.substring(0, end)) > available) end--;
		return value.substring(0, end) + suffix;
	}
}
