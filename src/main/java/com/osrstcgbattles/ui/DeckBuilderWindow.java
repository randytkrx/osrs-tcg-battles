package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.CardAbility;
import com.osrstcgbattles.catalog.CardCategory;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckValidationError;
import com.osrstcgbattles.persist.DeckProfile;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;

/** Resizable tavern-workbench editor for profile-aware decks. */
public final class DeckBuilderWindow
{
	private static final Color READY = ColorScheme.PROGRESS_COMPLETE_COLOR;
	private static final Color WARNING = ColorScheme.PROGRESS_INPROGRESS_COLOR;
	private static final Color ERROR = ColorScheme.PROGRESS_ERROR_COLOR;

	private final BattleUiController controller;
	private final Map<String, Integer> quantities = new LinkedHashMap<>();
	private JFrame frame;
	private JLabel ownershipLabel;
	private JComboBox<DeckChoice> deckChooser;
	private JTextField nameField;
	private JTextField searchField;
	private JComboBox<String> categoryFilter;
	private JComboBox<String> ownershipFilter;
	private JComboBox<String> factionFilter;
	private JComboBox<String> rarityFilter;
	private JComboBox<String> sortFilter;
	private JLabel resultCount;
	private DefaultListModel<BattleCard> catalogModel;
	private JList<BattleCard> catalogList;
	private JLabel cardDetails;
	private JButton catalogRemove;
	private JButton catalogAdd;
	private DefaultListModel<DeckRow> deckModel;
	private JList<DeckRow> deckList;
	private JButton deckRemove;
	private JButton deckAdd;
	private JButton removeAll;
	private JButton deleteButton;
	private JButton duplicateButton;
	private JButton resetButton;
	private JButton saveButton;
	private JButton useButton;
	private JLabel statusLabel;
	private JLabel summaryLabel;
	private JLabel errorsLabel;
	private JProgressBar cardProgress;
	private ManaCurvePanel manaCurve;
	private String editingId;
	private Deck baseline;
	private boolean loading;

	public DeckBuilderWindow(BattleUiController controller)
	{
		this.controller = java.util.Objects.requireNonNull(controller, "controller");
		runOnEdtAndWait(this::initialize);
	}

	public void showWindow()
	{
		SwingUtilities.invokeLater(() -> {
			if (!frame.isVisible()) reloadProfile(editingId);
			frame.setVisible(true);
			frame.toFront();
		});
	}

	public void dispose()
	{
		if (SwingUtilities.isEventDispatchThread()) frame.dispose();
		else SwingUtilities.invokeLater(() -> frame.dispose());
	}

	public void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}
		updateOwnershipBanner();
		rebuildCatalog();
		updateDeckSummary();
	}

	private void initialize()
	{
		frame = new JFrame("OSRS TCG Deck Workbench");
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.setMinimumSize(new Dimension(820, 560));
		frame.setSize(1080, 700);
		frame.setLocationByPlatform(true);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent event)
			{
				if (confirmAbandonChanges()) frame.setVisible(false);
			}
		});

		JPanel root = new JPanel(new BorderLayout(8, 8));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		root.add(buildOwnershipBanner(), BorderLayout.NORTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildCatalogPanel(), buildDeckPanel());
		split.setResizeWeight(.58);
		split.setDividerLocation(610);
		split.setDividerSize(7);
		split.setBorder(null);
		split.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.add(split, BorderLayout.CENTER);
		root.add(buildSavePanel(), BorderLayout.SOUTH);
		frame.setContentPane(root);
		reloadProfile(null);
	}

	private JPanel buildOwnershipBanner()
	{
		JPanel banner = new JPanel(new BorderLayout(8, 0));
		banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 6));
		ownershipLabel = new JLabel();
		ownershipLabel.setForeground(ColorScheme.TEXT_COLOR);
		ownershipLabel.setFont(ownershipLabel.getFont().deriveFont(Font.BOLD));
		JButton refresh = new JButton("Refresh Collection");
		styleButton(refresh, ColorScheme.BRAND_ORANGE);
		refresh.addActionListener(event -> controller.refreshCollection());
		banner.add(ownershipLabel, BorderLayout.CENTER);
		banner.add(refresh, BorderLayout.EAST);
		return banner;
	}

	private JPanel buildCatalogPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(6, 6));
		styleSection(panel, "CARD LIBRARY");

		JPanel filters = new JPanel(new BorderLayout(4, 4));
		filters.setOpaque(false);
		JPanel search = new JPanel(new BorderLayout(5, 0));
		search.setOpaque(false);
		JLabel searchLabel = new JLabel("Search");
		searchLabel.setForeground(ColorScheme.TEXT_COLOR);
		searchField = new JTextField();
		styleInput(searchField);
		resultCount = new JLabel("0 cards", SwingConstants.RIGHT);
		resultCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		search.add(searchLabel, BorderLayout.WEST);
		search.add(searchField, BorderLayout.CENTER);
		search.add(resultCount, BorderLayout.EAST);
		filters.add(search, BorderLayout.NORTH);

		JPanel choices = new JPanel(new GridLayout(2, 3, 4, 4));
		choices.setOpaque(false);
		categoryFilter = new JComboBox<>(new String[]{"All types", "Units", "Specials"});
		ownershipFilter = new JComboBox<>(new String[]{"All ownership", "Owned", "Missing"});
		factionFilter = new JComboBox<>(factionChoices());
		rarityFilter = new JComboBox<>(rarityChoices());
		sortFilter = new JComboBox<>(new String[]{"Sort: Mana", "Sort: Name", "Sort: Rarity", "Sort: Faction"});
		JButton clearFilters = new JButton("Clear Filters");
		styleButton(clearFilters, ColorScheme.MEDIUM_GRAY_COLOR);
		choices.add(categoryFilter);
		choices.add(ownershipFilter);
		choices.add(factionFilter);
		choices.add(rarityFilter);
		choices.add(sortFilter);
		choices.add(clearFilters);
		filters.add(choices, BorderLayout.CENTER);
		panel.add(filters, BorderLayout.NORTH);

		catalogModel = new DefaultListModel<>();
		catalogList = new JList<>(catalogModel);
		catalogList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		catalogList.setCellRenderer(new CardRenderer());
		catalogList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		catalogList.setForeground(ColorScheme.TEXT_COLOR);
		catalogList.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		catalogList.setSelectionForeground(Color.WHITE);
		catalogList.setFixedCellHeight(48);
		BattleCardTooltips.install(catalogList, index -> index >= 0 && index < catalogModel.size()
			? catalogModel.get(index) : null);
		catalogList.addListSelectionListener(event -> updateCardDetails());
		catalogList.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent event)
			{
				if (event.getClickCount() == 2) changeCatalogQuantity(1);
			}
		});
		catalogList.addKeyListener(new KeyAdapter()
		{
			@Override public void keyPressed(KeyEvent event)
			{
				if (event.getKeyCode() == KeyEvent.VK_ENTER || event.getKeyCode() == KeyEvent.VK_SPACE)
					changeCatalogQuantity(1);
			}
		});
		panel.add(new JScrollPane(catalogList), BorderLayout.CENTER);

		JPanel details = new JPanel(new BorderLayout(6, 4));
		details.setOpaque(false);
		cardDetails = new JLabel("Select a card to inspect its rules.");
		cardDetails.setForeground(ColorScheme.TEXT_COLOR);
		cardDetails.setVerticalAlignment(SwingConstants.TOP);
		cardDetails.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		cardDetails.setPreferredSize(new Dimension(0, 106));
		details.add(cardDetails, BorderLayout.CENTER);
		JPanel quantity = new JPanel(new GridLayout(1, 2, 4, 0));
		quantity.setOpaque(false);
		catalogRemove = new JButton("Remove Copy");
		catalogAdd = new JButton("Add Copy");
		styleButton(catalogRemove, ColorScheme.PROGRESS_ERROR_COLOR);
		styleButton(catalogAdd, ColorScheme.BRAND_ORANGE);
		catalogRemove.addActionListener(event -> changeCatalogQuantity(-1));
		catalogAdd.addActionListener(event -> changeCatalogQuantity(1));
		quantity.add(catalogRemove);
		quantity.add(catalogAdd);
		details.add(quantity, BorderLayout.SOUTH);
		panel.add(details, BorderLayout.SOUTH);

		DocumentListener listener = documentListener(this::rebuildCatalog);
		searchField.getDocument().addDocumentListener(listener);
		categoryFilter.addActionListener(event -> rebuildCatalog());
		ownershipFilter.addActionListener(event -> rebuildCatalog());
		factionFilter.addActionListener(event -> rebuildCatalog());
		rarityFilter.addActionListener(event -> rebuildCatalog());
		sortFilter.addActionListener(event -> rebuildCatalog());
		clearFilters.addActionListener(event -> {
			searchField.setText("");
			categoryFilter.setSelectedIndex(0);
			ownershipFilter.setSelectedIndex(0);
			factionFilter.setSelectedIndex(0);
			rarityFilter.setSelectedIndex(0);
			sortFilter.setSelectedIndex(0);
		});
		return panel;
	}

	private JPanel buildDeckPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(6, 6));
		styleSection(panel, "DECK LEDGER");

		JPanel header = new JPanel(new BorderLayout(4, 4));
		header.setOpaque(false);
		deckChooser = new JComboBox<>();
		deckChooser.addActionListener(event -> chooseDeck());
		header.add(deckChooser, BorderLayout.NORTH);
		nameField = new JTextField();
		styleInput(nameField);
		nameField.setBorder(BorderFactory.createTitledBorder("Deck name"));
		nameField.getDocument().addDocumentListener(documentListener(() -> {
			updateDeckSummary();
			checkpointDraft();
		}));
		header.add(nameField, BorderLayout.CENTER);

		JPanel actions = new JPanel(new GridLayout(1, 5, 3, 0));
		actions.setOpaque(false);
		JButton newButton = new JButton("New");
		duplicateButton = new JButton("Duplicate");
		deleteButton = new JButton("Delete");
		resetButton = new JButton("Reset");
		JButton clearButton = new JButton("Clear");
		styleButton(newButton, ColorScheme.MEDIUM_GRAY_COLOR);
		styleButton(duplicateButton, ColorScheme.BRAND_ORANGE);
		styleButton(deleteButton, ColorScheme.PROGRESS_ERROR_COLOR);
		styleButton(resetButton, WARNING);
		styleButton(clearButton, ColorScheme.PROGRESS_ERROR_COLOR);
		newButton.addActionListener(event -> newDeck());
		duplicateButton.addActionListener(event -> duplicateDeck());
		deleteButton.addActionListener(event -> deleteDeck());
		resetButton.addActionListener(event -> resetStarter());
		clearButton.addActionListener(event -> clearDeck());
		actions.add(newButton);
		actions.add(duplicateButton);
		actions.add(deleteButton);
		actions.add(resetButton);
		actions.add(clearButton);
		header.add(actions, BorderLayout.SOUTH);
		panel.add(header, BorderLayout.NORTH);

		deckModel = new DefaultListModel<>();
		deckList = new JList<>(deckModel);
		deckList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		deckList.setCellRenderer(new DeckRenderer());
		deckList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		deckList.setForeground(ColorScheme.TEXT_COLOR);
		deckList.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		deckList.setSelectionForeground(Color.WHITE);
		deckList.setFixedCellHeight(34);
		BattleCardTooltips.install(deckList, index -> index >= 0 && index < deckModel.size()
			? deckModel.get(index).card : null);
		deckList.addListSelectionListener(event -> updateDeckQuantityControls());
		deckList.addMouseListener(new MouseAdapter()
		{
			@Override public void mouseClicked(MouseEvent event)
			{
				if (event.getClickCount() == 2) changeDeckQuantity(-1);
			}
		});
		deckList.addKeyListener(new KeyAdapter()
		{
			@Override public void keyPressed(KeyEvent event)
			{
				if (event.getKeyCode() == KeyEvent.VK_DELETE || event.getKeyCode() == KeyEvent.VK_BACK_SPACE)
					changeDeckQuantity(-1);
			}
		});

		JPanel ledger = new JPanel(new BorderLayout(4, 4));
		ledger.setOpaque(false);
		ledger.add(new JScrollPane(deckList), BorderLayout.CENTER);
		JPanel rowActions = new JPanel(new GridLayout(1, 3, 4, 0));
		rowActions.setOpaque(false);
		deckRemove = new JButton("- Copy");
		deckAdd = new JButton("+ Copy");
		removeAll = new JButton("Remove Card");
		styleButton(deckRemove, ColorScheme.PROGRESS_ERROR_COLOR);
		styleButton(deckAdd, ColorScheme.BRAND_ORANGE);
		styleButton(removeAll, ERROR);
		deckRemove.addActionListener(event -> changeDeckQuantity(-1));
		deckAdd.addActionListener(event -> changeDeckQuantity(1));
		removeAll.addActionListener(event -> removeSelectedCard());
		rowActions.add(deckRemove);
		rowActions.add(deckAdd);
		rowActions.add(removeAll);
		ledger.add(rowActions, BorderLayout.SOUTH);
		panel.add(ledger, BorderLayout.CENTER);

		JPanel analysis = new JPanel(new BorderLayout(5, 5));
		analysis.setOpaque(false);
		statusLabel = new JLabel();
		statusLabel.setOpaque(true);
		statusLabel.setForeground(Color.WHITE);
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
		statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		analysis.add(statusLabel, BorderLayout.NORTH);
		JPanel metrics = new JPanel(new GridLayout(3, 1, 0, 3));
		metrics.setOpaque(false);
		cardProgress = new JProgressBar(0, 30);
		cardProgress.setStringPainted(true);
		cardProgress.setForeground(ColorScheme.BRAND_ORANGE);
		cardProgress.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		summaryLabel = new JLabel();
		summaryLabel.setForeground(ColorScheme.TEXT_COLOR);
		manaCurve = new ManaCurvePanel();
		manaCurve.setPreferredSize(new Dimension(0, 58));
		metrics.add(cardProgress);
		metrics.add(summaryLabel);
		metrics.add(manaCurve);
		analysis.add(metrics, BorderLayout.CENTER);
		errorsLabel = new JLabel();
		errorsLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR.brighter());
		errorsLabel.setVerticalAlignment(SwingConstants.TOP);
		errorsLabel.setBorder(BorderFactory.createEmptyBorder(3, 2, 0, 2));
		analysis.add(errorsLabel, BorderLayout.SOUTH);
		panel.add(analysis, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildSavePanel()
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		panel.setOpaque(false);
		saveButton = new JButton("Save Draft");
		useButton = new JButton("Use Deck");
		styleButton(saveButton, ColorScheme.MEDIUM_GRAY_COLOR);
		styleButton(useButton, ColorScheme.BRAND_ORANGE);
		saveButton.addActionListener(event -> save(false));
		useButton.addActionListener(event -> save(true));
		panel.add(saveButton);
		panel.add(useButton);
		return panel;
	}

	private void reloadProfile(String preferredId)
	{
		updateOwnershipBanner();
		DeckProfile profile = controller.getDeckProfile();
		String selectedId = profile.getSelectedDeckId().orElse(null);
		loading = true;
		DefaultComboBoxModel<DeckChoice> model = new DefaultComboBoxModel<>();
		Deck preferred = null;
		for (Deck deck : profile.getDecks())
		{
			model.addElement(new DeckChoice(deck, selectedId, controller.getDeckReadiness(deck)));
			if (deck.getId().equals(preferredId) || (preferredId == null && deck.getId().equals(selectedId))) preferred = deck;
		}
		deckChooser.setModel(model);
		loading = false;
		if (preferred != null)
		{
			loadDeck(preferred);
			selectChoice(preferred.getId());
		}
		else if (model.getSize() > 0)
		{
			loadDeck(model.getElementAt(0).deck);
			selectChoice(model.getElementAt(0).deck.getId());
		}
		else newDeckWithoutPrompt();
		rebuildCatalog();
	}

	private void chooseDeck()
	{
		if (loading) return;
		DeckChoice choice = (DeckChoice) deckChooser.getSelectedItem();
		if (choice == null || choice.deck.getId().equals(editingId)) return;
		if (confirmAbandonChanges()) loadDeck(choice.deck);
		else selectChoice(editingId);
	}

	private void loadDeck(Deck deck)
	{
		loading = true;
		editingId = deck.getId();
		baseline = deck;
		quantities.clear();
		quantities.putAll(BattleUiFormatters.quantities(deck));
		nameField.setText(deck.getName());
		loading = false;
		updateDeckSummary();
		updateCardDetails();
	}

	private void newDeck()
	{
		if (confirmAbandonChanges()) newDeckWithoutPrompt();
	}

	private void newDeckWithoutPrompt()
	{
		loading = true;
		editingId = UUID.randomUUID().toString();
		baseline = null;
		quantities.clear();
		nameField.setText("New Deck");
		deckChooser.setSelectedItem(null);
		loading = false;
		updateDeckSummary();
		checkpointDraft();
		nameField.selectAll();
		nameField.requestFocusInWindow();
	}

	private void duplicateDeck()
	{
		Deck source = currentDeck();
		if (source == null) return;
		loading = true;
		editingId = UUID.randomUUID().toString();
		baseline = null;
		String name = source.getName().replaceFirst("^Starter:\\s*", "");
		nameField.setText(name + " Copy");
		deckChooser.setSelectedItem(null);
		loading = false;
		updateDeckSummary();
		checkpointDraft();
		nameField.selectAll();
		nameField.requestFocusInWindow();
	}

	private void deleteDeck()
	{
		if (editingId == null || baseline == null || controller.isStarterId(editingId)) return;
		if (JOptionPane.showConfirmDialog(frame, "Delete '" + baseline.getName() + "'?", "Delete deck",
			JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
		{
			controller.deleteDeck(editingId);
			baseline = null;
			reloadProfile(null);
		}
	}

	private void resetStarter()
	{
		if (!controller.isStarterId(editingId)) return;
		Deck starter = controller.getStarterDecks().stream().filter(deck -> deck.getId().equals(editingId))
			.findFirst().orElse(null);
		if (starter != null && JOptionPane.showConfirmDialog(frame, "Reset this starter to its original cards?",
			"Reset starter", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
		{
			controller.saveDeck(starter, false);
			reloadProfile(starter.getId());
		}
	}

	private void clearDeck()
	{
		if (isReadOnlyStarter() || quantities.isEmpty()) return;
		if (JOptionPane.showConfirmDialog(frame, "Remove every card from this draft?", "Clear deck",
			JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
		{
			quantities.clear();
			updateDeckSummary();
			rebuildCatalog();
			checkpointDraft();
		}
	}

	private void save(boolean select)
	{
		Deck deck = currentDeck();
		DeckReadiness readiness = controller.getDeckReadiness(deck);
		if (select && !readiness.isPlayable()) return;
		controller.saveDeck(deck, select);
		reloadProfile(deck.getId());
	}

	private boolean confirmAbandonChanges()
	{
		if (!isDirty()) return true;
		int option = JOptionPane.showOptionDialog(frame, "Save changes to '" + currentDeck().getName() + "'?",
			"Unsaved deck", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null,
			new Object[]{"Save Draft", "Discard", "Cancel"}, "Save Draft");
		if (option == 0)
		{
			controller.saveDeck(currentDeck(), false);
			baseline = currentDeck();
			updateChoice(baseline);
			return true;
		}
		if (option == 1)
		{
			discardChanges();
			return true;
		}
		return false;
	}

	private void checkpointDraft()
	{
		if (loading || !isDirty() || isReadOnlyStarter()) return;
		Deck draft = currentDeck();
		controller.saveDeck(draft, false);
		updateChoice(draft);
	}

	private void discardChanges()
	{
		if (baseline == null)
		{
			controller.deleteDeck(editingId);
			removeChoice(editingId);
		}
		else
		{
			controller.saveDeck(baseline, false);
			updateChoice(baseline);
		}
	}

	private Deck currentDeck()
	{
		if (editingId == null) return null;
		String name = nameField.getText().trim();
		return new Deck(editingId, name.isEmpty() ? "Untitled Deck" : name,
			BattleUiFormatters.entries(quantities));
	}

	private boolean isDirty()
	{
		Deck current = currentDeck();
		return current != null && !current.equals(baseline);
	}

	private boolean isReadOnlyStarter()
	{
		Deck current = currentDeck();
		return current != null && controller.getDeckReadiness(current).getStatus()
			== DeckReadiness.Status.BUILT_IN_STARTER;
	}

	private void changeCatalogQuantity(int amount)
	{
		BattleCard card = catalogList.getSelectedValue();
		if (card == null || isReadOnlyStarter()) return;
		changeQuantity(card, amount);
	}

	private void changeDeckQuantity(int amount)
	{
		DeckRow row = deckList.getSelectedValue();
		if (row == null || isReadOnlyStarter()) return;
		changeQuantity(row.card, amount);
	}

	private void changeQuantity(BattleCard card, int amount)
	{
		int next = Math.max(0, Math.min(controller.copyLimit(card),
			quantities.getOrDefault(card.getId(), 0) + amount));
		if (next == 0) quantities.remove(card.getId());
		else quantities.put(card.getId(), next);
		updateDeckSummary();
		updateCardDetails();
		rebuildCatalog();
		checkpointDraft();
	}

	private void removeSelectedCard()
	{
		DeckRow row = deckList.getSelectedValue();
		if (row == null || isReadOnlyStarter()) return;
		quantities.remove(row.card.getId());
		updateDeckSummary();
		rebuildCatalog();
		checkpointDraft();
	}

	private void rebuildCatalog()
	{
		if (catalogModel == null || loading) return;
		String selectedId = catalogList.getSelectedValue() == null ? null : catalogList.getSelectedValue().getId();
		String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		List<BattleCard> cards = new ArrayList<>();
		for (BattleCard card : controller.getCatalog().getCards())
		{
			boolean categoryMatches = categoryFilter.getSelectedIndex() == 0
				|| (categoryFilter.getSelectedIndex() == 1 && card.getCategory() == CardCategory.UNIT)
				|| (categoryFilter.getSelectedIndex() == 2 && card.getCategory() == CardCategory.SPECIAL);
			boolean owned = collection.owns(card.getAzCardName());
			boolean ownershipMatches = BattleUiFormatters.ownershipMatches(ownershipFilter.getSelectedIndex(),
				collection.isKnown(), owned);
			boolean factionMatches = factionFilter.getSelectedIndex() == 0
				|| card.getFaction().name().equals(factionFilter.getSelectedItem());
			boolean rarityMatches = rarityFilter.getSelectedIndex() == 0
				|| card.getRarity().name().equals(rarityFilter.getSelectedItem());
			if (categoryMatches && ownershipMatches && factionMatches && rarityMatches
				&& searchable(card).contains(query)) cards.add(card);
		}
		cards.sort(cardComparator());
		catalogModel.clear();
		for (BattleCard card : cards)
		{
			catalogModel.addElement(card);
			if (card.getId().equals(selectedId)) catalogList.setSelectedIndex(catalogModel.size() - 1);
		}
		resultCount.setText(cards.size() + (cards.size() == 1 ? " card" : " cards"));
		updateCardDetails();
	}

	private String searchable(BattleCard card)
	{
		StringBuilder value = new StringBuilder(card.getDisplayName()).append(' ').append(card.getRulesText())
			.append(' ').append(card.getFaction()).append(' ').append(card.getRarity()).append(' ')
			.append(card.getCategory()).append(' ').append(String.join(" ", card.getTags()));
		for (CardAbility ability : card.getAbilities()) value.append(' ').append(ability.getType());
		return value.toString().toLowerCase(Locale.ROOT);
	}

	private Comparator<BattleCard> cardComparator()
	{
		Comparator<BattleCard> name = Comparator.comparing(BattleCard::getDisplayName, String.CASE_INSENSITIVE_ORDER);
		switch (sortFilter.getSelectedIndex())
		{
			case 1: return name;
			case 2: return Comparator.comparing(BattleCard::getRarity).thenComparing(name);
			case 3: return Comparator.comparing(BattleCard::getFaction).thenComparing(name);
			default: return Comparator.comparingInt(BattleCard::getManaCost).thenComparing(name);
		}
	}

	private void updateCardDetails()
	{
		if (cardDetails == null) return;
		BattleCard card = catalogList.getSelectedValue();
		boolean readOnly = isReadOnlyStarter();
		if (card == null)
		{
			cardDetails.setText("Select a card to inspect its rules.");
			catalogAdd.setEnabled(false);
			catalogRemove.setEnabled(false);
			return;
		}
		int count = quantities.getOrDefault(card.getId(), 0);
		int limit = controller.copyLimit(card);
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		String stats = card.getCategory() == CardCategory.UNIT
			? card.getAttack() + " ATK  /  " + card.getHealth() + " HP  /  " : "";
		cardDetails.setText("<html><b><font color='#dc8a00'>" + BattleCardTooltips.escapeHtml(card.getDisplayName())
			+ "</font></b> &nbsp; " + card.getFaction() + " " + card.getRarity() + "<br>"
			+ stats + card.getManaCost() + " mana &nbsp; | &nbsp; "
			+ BattleUiFormatters.ownershipMarker(collection.isKnown(), collection.owns(card.getAzCardName()))
			+ " &nbsp; | &nbsp; " + count + "/" + limit + " in deck<br><font color='#a5a5a5'>"
			+ BattleCardTooltips.escapeHtml(card.getRulesText()) + "</font></html>");
		catalogAdd.setEnabled(!readOnly && count < limit);
		catalogRemove.setEnabled(!readOnly && count > 0);
	}

	private void updateDeckSummary()
	{
		if (deckModel == null || editingId == null || loading) return;
		String selectedId = deckList.getSelectedValue() == null ? null : deckList.getSelectedValue().card.getId();
		List<DeckRow> rows = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : quantities.entrySet())
		{
			BattleCard card = controller.getCatalog().findById(entry.getKey()).orElse(null);
			if (card != null) rows.add(new DeckRow(card, entry.getValue()));
		}
		rows.sort(Comparator.comparingInt((DeckRow row) -> row.card.getManaCost())
			.thenComparing(row -> row.card.getDisplayName(), String.CASE_INSENSITIVE_ORDER));
		deckModel.clear();
		for (DeckRow row : rows)
		{
			deckModel.addElement(row);
			if (row.card.getId().equals(selectedId)) deckList.setSelectedIndex(deckModel.size() - 1);
		}

		Deck current = currentDeck();
		DeckReadiness readiness = controller.getDeckReadiness(current);
		int cards = BattleUiFormatters.cardCount(quantities);
		int mana = BattleUiFormatters.totalManaCost(controller.getCatalog(), quantities);
		long units = rows.stream().filter(row -> row.card.getCategory() == CardCategory.UNIT)
			.mapToInt(row -> row.quantity).sum();
		cardProgress.setValue(Math.min(30, cards));
		cardProgress.setString(cards + " / 30 cards");
		cardProgress.setForeground(cards == 30 ? READY : cards > 30 ? ERROR : ColorScheme.BRAND_ORANGE);
		summaryLabel.setText(String.format(Locale.ROOT, "%d units  |  %d specials  |  %.1f average mana",
			units, cards - units, cards == 0 ? 0D : (double) mana / cards));
		manaCurve.setRows(rows);
		statusLabel.setText(readiness.getLabel() + (isDirty() ? "  *" : ""));
		statusLabel.setBackground(readiness.isPlayable() ? READY
			: readiness.getStatus() == DeckReadiness.Status.OWNERSHIP_PENDING ? WARNING : ERROR);
		statusLabel.setToolTipText(isDirty() ? "This deck has unsaved changes" : null);
		setErrors(readiness);

		boolean starterId = controller.isStarterId(editingId);
		boolean readOnly = readiness.getStatus() == DeckReadiness.Status.BUILT_IN_STARTER;
		nameField.setEditable(!readOnly);
		deleteButton.setEnabled(baseline != null && !starterId);
		duplicateButton.setEnabled(current != null);
		resetButton.setEnabled(starterId && !readOnly);
		saveButton.setEnabled(!readOnly && isDirty());
		useButton.setEnabled(readiness.isPlayable());
		useButton.setText(readOnly ? "Use Starter" : "Save and Use");
		updateDeckQuantityControls();
		frame.setTitle("OSRS TCG Deck Workbench" + (isDirty() ? " *" : ""));
	}

	private void setErrors(DeckReadiness readiness)
	{
		if (readiness.getStatus() == DeckReadiness.Status.OWNERSHIP_PENDING)
		{
			errorsLabel.setText("<html>Refresh the collection before this custom deck can be used.</html>");
			return;
		}
		List<DeckValidationError> errors = readiness.getValidation().getErrors();
		if (errors.isEmpty())
		{
			errorsLabel.setText(" ");
			return;
		}
		StringBuilder text = new StringBuilder("<html>");
		for (int i = 0; i < Math.min(3, errors.size()); i++)
		{
			if (i > 0) text.append("<br>");
			text.append("&#8226; ").append(BattleCardTooltips.escapeHtml(errors.get(i).getMessage()));
		}
		if (errors.size() > 3) text.append("<br>+").append(errors.size() - 3).append(" more");
		errorsLabel.setText(text.append("</html>").toString());
	}

	private void updateDeckQuantityControls()
	{
		if (deckRemove == null) return;
		DeckRow row = deckList.getSelectedValue();
		boolean editable = row != null && !isReadOnlyStarter();
		deckRemove.setEnabled(editable && row.quantity > 0);
		deckAdd.setEnabled(editable && row.quantity < controller.copyLimit(row.card));
		removeAll.setEnabled(editable);
	}

	private void updateOwnershipBanner()
	{
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		ownershipFilter.setEnabled(collection.isKnown());
		if (!collection.isKnown()) ownershipFilter.setSelectedIndex(0);
		ownershipLabel.setText(BattleUiFormatters.collection(collection)
			+ (collection.isKnown() ? " - custom decks are checked against your collection"
			: " - built-in starters remain playable"));
		ownershipLabel.getParent().setBackground(collection.isKnown()
			? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR);
	}

	private void selectChoice(String id)
	{
		if (id == null) return;
		loading = true;
		for (int i = 0; i < deckChooser.getItemCount(); i++)
		{
			if (deckChooser.getItemAt(i).deck.getId().equals(id))
			{
				deckChooser.setSelectedIndex(i);
				break;
			}
		}
		loading = false;
	}

	private void updateChoice(Deck deck)
	{
		if (deck == null) return;
		String selectedId = controller.getDeckProfile().getSelectedDeckId().orElse(null);
		loading = true;
		DefaultComboBoxModel<DeckChoice> model = (DefaultComboBoxModel<DeckChoice>) deckChooser.getModel();
		int index = -1;
		for (int i = 0; i < model.getSize(); i++)
		{
			if (model.getElementAt(i).deck.getId().equals(deck.getId()))
			{
				index = i;
				break;
			}
		}
		DeckChoice choice = new DeckChoice(deck, selectedId, controller.getDeckReadiness(deck));
		if (index >= 0) model.removeElementAt(index);
		else index = model.getSize();
		model.insertElementAt(choice, index);
		deckChooser.setSelectedItem(choice);
		loading = false;
	}

	private void removeChoice(String deckId)
	{
		loading = true;
		DefaultComboBoxModel<DeckChoice> model = (DefaultComboBoxModel<DeckChoice>) deckChooser.getModel();
		for (int i = 0; i < model.getSize(); i++)
		{
			if (model.getElementAt(i).deck.getId().equals(deckId))
			{
				model.removeElementAt(i);
				break;
			}
		}
		loading = false;
	}

	private static String[] factionChoices()
	{
		com.osrstcgbattles.catalog.Faction[] values = com.osrstcgbattles.catalog.Faction.values();
		String[] choices = new String[values.length + 1];
		choices[0] = "All factions";
		for (int i = 0; i < values.length; i++) choices[i + 1] = values[i].name();
		return choices;
	}

	private static String[] rarityChoices()
	{
		com.osrstcgbattles.catalog.Rarity[] values = com.osrstcgbattles.catalog.Rarity.values();
		String[] choices = new String[values.length + 1];
		choices[0] = "All rarities";
		for (int i = 0; i < values.length; i++) choices[i + 1] = values[i].name();
		return choices;
	}

	private static DocumentListener documentListener(Runnable action)
	{
		return new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent event) { action.run(); }
			@Override public void removeUpdate(DocumentEvent event) { action.run(); }
			@Override public void changedUpdate(DocumentEvent event) { action.run(); }
		};
	}

	private static void runOnEdtAndWait(Runnable action)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			action.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(action);
		}
		catch (InterruptedException exception)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while creating deck builder", exception);
		}
		catch (InvocationTargetException exception)
		{
			throw new IllegalStateException("Could not create deck builder", exception.getCause());
		}
	}

	private final class CardRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean selected, boolean focus)
		{
			BattleCard card = (BattleCard) value;
			int count = quantities.getOrDefault(card.getId(), 0);
			OwnedCardCollectionSnapshot collection = controller.getCollection();
			String marker = BattleUiFormatters.ownershipMarker(collection.isKnown(),
				collection.owns(card.getAzCardName()));
			String stats = card.getCategory() == CardCategory.UNIT
				? card.getAttack() + "/" + card.getHealth() + "  " : "";
			String text = "<html><b>" + BattleCardTooltips.escapeHtml(card.getDisplayName())
				+ "</b> &nbsp; <font color='#dc8a00'>" + card.getManaCost() + " mana</font> &nbsp; "
				+ stats + card.getRarity() + " &nbsp; [" + marker + "]"
				+ (count == 0 ? "" : " &nbsp; <b>x" + count + "</b>")
				+ "<br><font color='#a5a5a5'>" + BattleCardTooltips.escapeHtml(card.getRulesText()) + "</font></html>";
			JLabel label = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focus);
			label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0,
				ColorScheme.BRAND_ORANGE), BorderFactory.createEmptyBorder(2, 6, 2, 5)));
			return label;
		}
	}

	private final class DeckRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
			boolean selected, boolean focus)
		{
			DeckRow row = (DeckRow) value;
			OwnedCardCollectionSnapshot collection = controller.getCollection();
			String marker = collection.isKnown() && !collection.owns(row.card.getAzCardName()) ? "MISSING" : "";
			String text = "<html><font color='#dc8a00'><b>" + row.card.getManaCost() + "</b></font> &nbsp; "
				+ BattleCardTooltips.escapeHtml(row.card.getDisplayName()) + " &nbsp; <b>x" + row.quantity + "</b>"
				+ (marker.isEmpty() ? "" : " &nbsp; <font color='#e61e1e'>" + marker + "</font>") + "</html>";
			JLabel label = (JLabel) super.getListCellRendererComponent(list, text, index, selected, focus);
			label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0,
				ColorScheme.BRAND_ORANGE), BorderFactory.createEmptyBorder(1, 6, 1, 5)));
			return label;
		}
	}

	private static final class DeckRow
	{
		private final BattleCard card;
		private final int quantity;

		private DeckRow(BattleCard card, int quantity)
		{
			this.card = card;
			this.quantity = quantity;
		}
	}

	private static final class DeckChoice
	{
		private final Deck deck;
		private final String text;

		private DeckChoice(Deck deck, String selectedId, DeckReadiness readiness)
		{
			this.deck = deck;
			this.text = deck.getName() + (deck.getId().equals(selectedId) ? "  [IN USE]" : "")
				+ (readiness.getStatus() == DeckReadiness.Status.BUILT_IN_STARTER ? "  [STARTER]" : "");
		}

		@Override public String toString() { return text; }
	}

	private static final class ManaCurvePanel extends JPanel
	{
		private int[] curve = new int[11];

		private ManaCurvePanel()
		{
			setOpaque(false);
			setToolTipText("Mana curve: costs 0 through 10+");
		}

		private void setRows(List<DeckRow> rows)
		{
			curve = new int[11];
			for (DeckRow row : rows) curve[Math.min(10, row.card.getManaCost())] += row.quantity;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int max = 1;
			for (int count : curve) max = Math.max(max, count);
			int gap = 2;
			int barWidth = Math.max(3, (getWidth() - gap * 10) / 11);
			for (int i = 0; i < curve.length; i++)
			{
				int height = (getHeight() - 16) * curve[i] / max;
				int x = i * (barWidth + gap);
				g.setColor(ColorScheme.BRAND_ORANGE);
				g.fillRoundRect(x, getHeight() - 13 - height, barWidth, height, 4, 4);
				g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g.drawString(i == 10 ? "+" : Integer.toString(i), x + 2, getHeight() - 1);
			}
			g.dispose();
		}
	}

	private static void styleSection(JPanel panel, String title)
	{
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
			title, 0, 0, null, ColorScheme.BRAND_ORANGE));
	}

	private static void styleButton(JButton button, Color accent)
	{
		button.setForeground(ColorScheme.TEXT_COLOR);
		button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent.darker()),
			BorderFactory.createEmptyBorder(4, 7, 4, 7)));
	}

	private static void styleInput(JTextField field)
	{
		field.setForeground(ColorScheme.TEXT_COLOR);
		field.setCaretColor(ColorScheme.TEXT_COLOR);
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ColorScheme.BORDER_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));
	}
}
