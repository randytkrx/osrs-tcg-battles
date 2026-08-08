package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.engine.GwentEngine;
import com.osrstcgbattles.persist.DeckProfile;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import com.osrstcgbattles.party.PartyOpponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public final class OsrsTcgBattlesPanel extends PluginPanel
{
	private final BattleUiController controller;
	private final JLabel collectionLabel = new JLabel();
	private final JLabel catalogLabel = new JLabel();
	private final JLabel deckLabel = new JLabel();
	private final JLabel validationLabel = new JLabel();
	private final JButton refreshButton = new JButton("Refresh Collection");
	private final JButton builderButton = new JButton("Deck Builder");
	private final JButton demoButton = new JButton("Local Demo");
	private final JLabel partyStatusLabel = new JLabel();
	private final JComboBox<PartyOpponent> opponentCombo = new JComboBox<>();
	private final JButton inviteButton = new JButton("Invite");
	private final JButton acceptButton = new JButton("Accept");
	private final JButton declineButton = new JButton("Decline");
	private final JButton abortButton = new JButton("Abort");
	private boolean active = true;

	public OsrsTcgBattlesPanel(BattleUiController controller)
	{
		this.controller = java.util.Objects.requireNonNull(controller, "controller");
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		JPanel play = new JPanel(new GridBagLayout());
		play.setOpaque(false);
		GridBagConstraints playLayout = new GridBagConstraints();
		playLayout.gridx = 0;
		playLayout.weightx = 1.0;
		playLayout.weighty = 0.0;
		playLayout.fill = GridBagConstraints.HORIZONTAL;
		playLayout.insets = new Insets(0, 0, 8, 0);

		JPanel status = new JPanel(new GridLayout(0, 1, 0, 5));
		status.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		status.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 7, 6, 7)));
		status.add(collectionLabel);
		status.add(catalogLabel);
		status.add(deckLabel);
		status.add(validationLabel);

		JPanel friendDuel = new JPanel(new GridBagLayout());
		friendDuel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createTitledBorder("Friend Duel"),
			BorderFactory.createEmptyBorder(4, 6, 6, 6)));
		GridBagConstraints layout = new GridBagConstraints();
		layout.gridx = 0;
		layout.weightx = 1.0;
		layout.weighty = 0.0;
		layout.fill = GridBagConstraints.HORIZONTAL;
		layout.insets = new Insets(0, 0, 5, 0);
		layout.gridy = 0;
		friendDuel.add(partyStatusLabel, layout);
		opponentCombo.setRenderer(new PartyOpponentRenderer());
		layout.gridy = 1;
		friendDuel.add(opponentCombo, layout);
		JPanel duelButtons = new JPanel(new GridLayout(2, 2, 4, 4));
		inviteButton.addActionListener(event -> {
			PartyOpponent opponent = (PartyOpponent) opponentCombo.getSelectedItem();
			if (active && opponent != null)
			{
				controller.invitePartyOpponent(opponent.getMemberId());
			}
		});
		acceptButton.addActionListener(event -> {
			if (active) controller.acceptPartyDuel();
		});
		declineButton.addActionListener(event -> {
			if (active) controller.declinePartyDuel();
		});
		abortButton.addActionListener(event -> {
			if (active) controller.abortPartyDuel();
		});
		duelButtons.add(inviteButton);
		duelButtons.add(acceptButton);
		duelButtons.add(declineButton);
		duelButtons.add(abortButton);
		layout.gridy = 2;
		layout.insets = new Insets(0, 0, 0, 0);
		friendDuel.add(duelButtons, layout);

		// The status block and the friend duel box must keep their natural height; placing them in
		// a NORTH column stops the titled box from stretching to fill the whole tab (which is what
		// made it render as a huge box that pushed the action buttons out of view).
		JPanel top = new JPanel(new GridBagLayout());
		top.setOpaque(false);
		GridBagConstraints topLayout = new GridBagConstraints();
		topLayout.gridx = 0;
		topLayout.weightx = 1.0;
		topLayout.weighty = 0.0;
		topLayout.fill = GridBagConstraints.HORIZONTAL;
		topLayout.insets = new Insets(0, 0, 8, 0);
		topLayout.gridy = 0;
		top.add(status, topLayout);
		topLayout.gridy = 1;
		topLayout.insets = new Insets(0, 0, 0, 0);
		top.add(friendDuel, topLayout);
		playLayout.gridy = 0;
		play.add(top, playLayout);

		JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
		refreshButton.addActionListener(event -> {
			if (!active)
			{
				return;
			}
			controller.refreshCollection();
			refresh();
		});
		builderButton.addActionListener(event -> {
			if (active)
			{
				controller.openDeckBuilder();
			}
		});
		demoButton.addActionListener(event -> {
			if (active)
			{
				controller.startDemoMatch();
			}
		});
		buttons.add(refreshButton);
		buttons.add(builderButton);
		buttons.add(demoButton);
		buttons.setPreferredSize(new Dimension(0, 76));
		playLayout.gridy = 1;
		playLayout.insets = new Insets(0, 0, 0, 0);
		play.add(buttons, playLayout);

		// With every row at weighty 0 GridBagLayout centers the whole grid vertically, which
		// pushed the content into the middle and left dead space above and below. A filler row
		// with weighty 1.0 absorbs the leftover space so everything sits at the top.
		playLayout.gridy = 2;
		playLayout.weighty = 1.0;
		play.add(Box.createVerticalGlue(), playLayout);

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Play", play);
		tabs.addTab("How to Play", buildRulesPanel());
		add(tabs, BorderLayout.CENTER);
		refresh();
	}

	private JPanel buildRulesPanel()
	{
		JEditorPane rules = new JEditorPane("text/html", rulesHtml());
		rules.setEditable(false);
		rules.setFocusable(false);
		rules.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		rules.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rules.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		JScrollPane scroll = new JScrollPane(rules);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	static String rulesHtml()
	{
		return "<html><body style='background:#1f1a15;color:#e2d6be;font-family:sans-serif;font-size:9px;'>"
			+ "<h2 style='color:#deB452;margin-bottom:3px'>OSRS TCG Battles</h2>"
			+ "<p>Build a <b>" + GwentEngine.DECK_SIZE + "-card deck</b>, reduce the enemy hero from <b>"
			+ GwentEngine.HERO_HEALTH + " health</b> to zero, and protect your own.</p>"
			+ section("1. Opening Hand", "Draw " + GwentEngine.OPENING_HAND_SIZE
				+ " cards. Click cards to replace them during the mulligan, then press <b>Keep Hand</b>.")
			+ section("2. Mana", "You begin with 1 mana crystal. Your maximum mana grows by one each turn, up to "
				+ GwentEngine.MAXIMUM_MANA + ", and refills at the start of your turn.")
			+ section("3. Playing Cards", "Click a glowing card in your hand. Units enter your battlefield; special cards "
				+ "resolve their effect immediately. You may control up to " + GwentEngine.BATTLEFIELD_LIMIT + " units.")
			+ section("4. Deploy Effects", "Some cards damage an enemy or strengthen an ally when played. After selecting "
				+ "the card, click a glowing target. <b>Hover any card to read its full effect.</b>")
			+ section("5. Combat", "New units have summoning sickness and cannot attack until your next turn. Select a ready "
				+ "unit, then attack an enemy unit or, if the enemy board is empty, the enemy hero. Units deal damage to "
				+ "each other simultaneously.")
			+ section("6. Hand and Fatigue", "Your hand holds at most " + GwentEngine.HAND_LIMIT
				+ " cards. When your deck is empty, failed draws deal increasing fatigue damage.")
			+ section("7. Controls", "<b>Right-click</b> or press <b>Esc</b> to cancel a selection. Press <b>End Turn</b> when "
				+ "finished. You may concede at any time.")
			+ section("Friend Duels", "Join the same RuneLite party, choose an eligible member on the Play tab, and send an "
				+ "invite. Both players need a valid selected deck and refreshed collection.")
			+ "<p style='color:#9f9685'><i>Tip: green borders are playable or ready, gold is selected, and red marks legal targets.</i></p>"
			+ "</body></html>";
	}

	private static String section(String title, String text)
	{
		return "<b style='color:#f1d58a'>" + title + "</b><br>" + text + "<br><br>";
	}

	public void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}
		if (!active)
		{
			return;
		}
		BattleCardCatalog catalog = controller.getCatalog();
		if (catalog == null)
		{
			reset();
			return;
		}
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		collectionLabel.setText("Collection: " + BattleUiFormatters.collection(collection));
		catalogLabel.setText("Catalog: " + catalog.getCards().size() + " cards");
		DeckProfile profile = controller.getDeckProfile();
		Deck selected = profile.getSelectedDeckId().flatMap(id -> profile.getDecks().stream()
			.filter(deck -> deck.getId().equals(id)).findFirst()).orElse(null);
		deckLabel.setText("Selected deck: " + (selected == null ? "None" : selected.getName()));
		validationLabel.setText(selected == null ? "Validation: No deck selected"
			: "Validation: " + BattleUiFormatters.validation(controller.validate(selected), collection.isKnown()));

		long selectedOpponentId = selectedOpponentId();
		List<PartyOpponent> opponents = controller.getPartyOpponents();
		opponentCombo.removeAllItems();
		for (PartyOpponent opponent : opponents)
		{
			opponentCombo.addItem(opponent);
			if (opponent.getMemberId() == selectedOpponentId)
			{
				opponentCombo.setSelectedItem(opponent);
			}
		}
		PartyDuelSnapshot duel = controller.getPartyDuelSnapshot();
		String message = controller.getPartyDuelMessage();
		partyStatusLabel.setText("<html>" + (message == null
			? BattleUiFormatters.partyDuelStatus(duel) : message) + "</html>");
		boolean hasOpponent = opponentCombo.getSelectedItem() != null;
		opponentCombo.setEnabled(duel.getStatus() == PartyDuelSnapshot.Status.IDLE
			|| duel.getStatus() == PartyDuelSnapshot.Status.TERMINAL);
		// A missing or invalid deck now falls back to a random 30-card deck, so the duel is
		// always startable; deck validation is still shown above for the player's information.
		inviteButton.setEnabled(BattleUiFormatters.canInvitePartyDuel(duel, hasOpponent, true));
		acceptButton.setEnabled(BattleUiFormatters.canAcceptPartyDuel(duel, true));
		declineButton.setEnabled(duel.canDecline());
		abortButton.setEnabled(duel.canAbort());
	}

	private long selectedOpponentId()
	{
		PartyOpponent selected = (PartyOpponent) opponentCombo.getSelectedItem();
		return selected == null ? 0 : selected.getMemberId();
	}

	public void reset()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::reset);
			return;
		}
		active = false;
		refreshButton.setEnabled(false);
		builderButton.setEnabled(false);
		demoButton.setEnabled(false);
		opponentCombo.setEnabled(false);
		inviteButton.setEnabled(false);
		acceptButton.setEnabled(false);
		declineButton.setEnabled(false);
		abortButton.setEnabled(false);
		collectionLabel.setText("Collection: Not loaded");
		catalogLabel.setText("Catalog: Not loaded");
		deckLabel.setText("Selected deck: None");
		validationLabel.setText("Validation: No deck selected");
		partyStatusLabel.setText("Friend duel unavailable");
	}

	private static final class PartyOpponentRenderer extends JLabel implements ListCellRenderer<PartyOpponent>
	{
		@Override
		public Component getListCellRendererComponent(JList<? extends PartyOpponent> list, PartyOpponent value,
			int index, boolean selected, boolean focused)
		{
			setOpaque(true);
			setText(value == null ? "No eligible party opponents" : value.getDisplayName());
			setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			setForeground(selected ? list.getSelectionForeground() : list.getForeground());
			return this;
		}
	}
}
