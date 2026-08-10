package com.osrstcgbattles.ui.board;

import java.awt.BorderLayout;
import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Compact in-board history of public battle events. */
final class BattleLogPanel extends JPanel
{
	private static final int MAX_ENTRIES = 80;
	private final JTextArea text = new JTextArea();
	private int entries;

	BattleLogPanel()
	{
		setLayout(new BorderLayout());
		setBackground(new Color(28, 21, 15, 245));
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BoardTheme.GOLD.darker()),
			BorderFactory.createEmptyBorder(4, 4, 4, 4)));
		text.setEditable(false);
		text.setFocusable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setForeground(BoardTheme.TEXT);
		text.setBackground(new Color(28, 21, 15));
		text.setFont(text.getFont().deriveFont(10f));
		JScrollPane scroll = new JScrollPane(text);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(12);
		add(scroll, BorderLayout.CENTER);
	}

	void append(String entry)
	{
		if (entry == null || entry.trim().isEmpty()) return;
		if (entries >= MAX_ENTRIES)
		{
			String current = text.getText();
			int newline = current.indexOf('\n');
			text.setText(newline < 0 ? "" : current.substring(newline + 1));
			entries--;
		}
		if (entries > 0) text.append("\n");
		text.append("• " + entry.trim());
		entries++;
		text.setCaretPosition(text.getDocument().getLength());
	}

	String text()
	{
		return text.getText();
	}
}
