package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.CardCategory;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckValidationError;
import com.osrstcgbattles.deck.DeckValidationResult;
import com.osrstcgbattles.persist.DeckProfile;
import com.osrstcgbattles.ui.board.BoardTheme;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Compact editor for persisted decks. All Swing access is marshalled to the EDT. */
public final class DeckBuilderWindow
{
	private final BattleUiController controller;
	private final Map<String, Integer> quantities = new LinkedHashMap<>();
	private JFrame frame;
	private JLabel ownershipBanner;
	private JComboBox<DeckChoice> deckChooser;
	private JTextField nameField;
	private JTextField searchField;
	private JComboBox<String> categoryFilter;
	private JComboBox<String> ownershipFilter;
	private DefaultListModel<BattleCard> catalogModel;
	private JList<BattleCard> catalogList;
	private DefaultListModel<String> deckModel;
	private JList<String> deckList;
	private JList<String> errorList;
	private JLabel totalsLabel;
	private JLabel selectedQuantityLabel;
	private JButton removeButton;
	private JButton addButton;
	private JButton selectButton;
	private String editingId;
	private boolean loading;

	public DeckBuilderWindow(BattleUiController controller)
	{
		this.controller = java.util.Objects.requireNonNull(controller, "controller");
		runOnEdtAndWait(this::initialize);
	}

	public void showWindow()
	{
		SwingUtilities.invokeLater(() -> {
			reloadProfile(editingId);
			frame.setVisible(true);
			frame.toFront();
		});
	}

	public void dispose()
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			frame.dispose();
		}
		else
		{
			SwingUtilities.invokeLater(() -> frame.dispose());
		}
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
	}

	private void initialize()
	{
		frame = new JFrame("OSRS TCG Deck Builder");
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setMinimumSize(new Dimension(700, 480));
		frame.setSize(820, 580);
		frame.setLocationByPlatform(true);

		JPanel root = new JPanel(new BorderLayout(8, 8));
		root.setBackground(BoardTheme.BACKGROUND);
		root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BoardTheme.GOLD.darker()),
			BorderFactory.createEmptyBorder(10, 10, 10, 10)));
		ownershipBanner = new JLabel();
		ownershipBanner.setOpaque(true);
		ownershipBanner.setForeground(BoardTheme.TEXT);
		ownershipBanner.setFont(ownershipBanner.getFont().deriveFont(java.awt.Font.BOLD));
		ownershipBanner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		root.add(ownershipBanner, BorderLayout.NORTH);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildCatalogPanel(), buildDeckPanel());
		split.setResizeWeight(0.52);
		split.setBorder(null);
		split.setDividerSize(6);
		split.setBackground(BoardTheme.BACKGROUND);
		root.add(split, BorderLayout.CENTER);
		root.add(buildSavePanel(), BorderLayout.SOUTH);
		frame.setContentPane(root);
		reloadProfile(null);
	}

	private JPanel buildCatalogPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		styleSection(panel, "CARD CATALOG");
		JPanel filters = new JPanel(new GridLayout(0, 1, 0, 4));
		filters.setOpaque(false);
		searchField = new JTextField();
		styleInput(searchField);
		searchField.setToolTipText("Search card names and rules");
		JPanel choices = new JPanel(new GridLayout(1, 2, 4, 0));
		choices.setOpaque(false);
		categoryFilter = new JComboBox<>(new String[]{"All categories", "Units", "Specials"});
		ownershipFilter = new JComboBox<>(new String[]{"All cards", "Owned only", "Unowned only"});
		choices.add(categoryFilter);
		choices.add(ownershipFilter);
		filters.add(searchField);
		filters.add(choices);
		panel.add(filters, BorderLayout.NORTH);

		catalogModel = new DefaultListModel<>();
		catalogList = new JList<>(catalogModel);
		catalogList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		catalogList.setCellRenderer(new CardRenderer());
		catalogList.setBackground(BoardTheme.ROW_BAND);
		catalogList.setForeground(BoardTheme.TEXT);
		catalogList.setSelectionBackground(BoardTheme.ROW_BAND_LIGHT);
		catalogList.setSelectionForeground(BoardTheme.TEXT);
		catalogList.setFixedCellHeight(40);
		BattleCardTooltips.install(catalogList, index -> index >= 0 && index < catalogModel.size()
			? catalogModel.get(index) : null);
		catalogList.addListSelectionListener(event -> updateQuantityControls());
		panel.add(new JScrollPane(catalogList), BorderLayout.CENTER);

		JPanel quantity = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
		quantity.setOpaque(false);
		removeButton = new JButton("-");
		selectedQuantityLabel = new JLabel("0 / 0");
		addButton = new JButton("+");
		styleButton(removeButton, BoardTheme.GLOW_TARGET);
		styleButton(addButton, BoardTheme.GLOW_LEGAL);
		selectedQuantityLabel.setForeground(BoardTheme.TEXT);
		removeButton.addActionListener(event -> changeSelectedQuantity(-1));
		addButton.addActionListener(event -> changeSelectedQuantity(1));
		quantity.add(removeButton);
		quantity.add(selectedQuantityLabel);
		quantity.add(addButton);
		panel.add(quantity, BorderLayout.SOUTH);

		DocumentListener filterListener = new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent event) { rebuildCatalog(); }
			@Override public void removeUpdate(DocumentEvent event) { rebuildCatalog(); }
			@Override public void changedUpdate(DocumentEvent event) { rebuildCatalog(); }
		};
		searchField.getDocument().addDocumentListener(filterListener);
		categoryFilter.addActionListener(event -> rebuildCatalog());
		ownershipFilter.addActionListener(event -> rebuildCatalog());
		return panel;
	}

	private JPanel buildDeckPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		styleSection(panel, "YOUR DECK");
		JPanel header = new JPanel(new BorderLayout(4, 4));
		header.setOpaque(false);
		deckChooser = new JComboBox<>();
		deckChooser.addActionListener(event -> {
			if (!loading)
			{
				DeckChoice choice = (DeckChoice) deckChooser.getSelectedItem();
				if (choice != null)
				{
					loadDeck(choice.deck);
				}
			}
		});
		header.add(deckChooser, BorderLayout.NORTH);
		JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
		actions.setOpaque(false);
		JButton newButton = new JButton("New");
		JButton deleteButton = new JButton("Delete");
		selectButton = new JButton("Select");
		styleButton(newButton, BoardTheme.TEXT_DIM);
		styleButton(deleteButton, BoardTheme.GLOW_TARGET);
		styleButton(selectButton, BoardTheme.GLOW_LEGAL);
		newButton.addActionListener(event -> newDeck());
		deleteButton.addActionListener(event -> deleteDeck());
		selectButton.addActionListener(event -> selectDeck());
		actions.add(newButton);
		actions.add(deleteButton);
		actions.add(selectButton);
		header.add(actions, BorderLayout.CENTER);
		nameField = new JTextField();
		styleInput(nameField);
		nameField.setBorder(BorderFactory.createTitledBorder("Name"));
		nameField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent event) { updateDeckSummary(); }
			@Override public void removeUpdate(DocumentEvent event) { updateDeckSummary(); }
			@Override public void changedUpdate(DocumentEvent event) { updateDeckSummary(); }
		});
		header.add(nameField, BorderLayout.SOUTH);
		panel.add(header, BorderLayout.NORTH);

		deckModel = new DefaultListModel<>();
		deckList = new JList<>(deckModel);
		deckList.setFocusable(false);
		deckList.setBackground(BoardTheme.ROW_BAND);
		deckList.setForeground(BoardTheme.TEXT);
		deckList.setFixedCellHeight(26);
		BattleCardTooltips.install(deckList, this::deckCardAt);
		JPanel content = new JPanel(new GridLayout(2, 1, 0, 5));
		content.setOpaque(false);
		content.add(new JScrollPane(deckList));
		errorList = new JList<>(new DefaultListModel<>());
		errorList.setForeground(new Color(220, 95, 85));
		errorList.setBackground(BoardTheme.ROW_BAND);
		JScrollPane errors = new JScrollPane(errorList);
		errors.setBorder(BorderFactory.createTitledBorder("Validation (saving is still allowed)"));
		content.add(errors);
		panel.add(content, BorderLayout.CENTER);
		totalsLabel = new JLabel();
		totalsLabel.setForeground(BoardTheme.GOLD);
		totalsLabel.setFont(totalsLabel.getFont().deriveFont(java.awt.Font.BOLD));
		totalsLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		panel.add(totalsLabel, BorderLayout.SOUTH);
		return panel;
	}

	private JPanel buildSavePanel()
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		panel.setOpaque(false);
		JButton save = new JButton("Save");
		JButton saveAndSelect = new JButton("Save & Select");
		styleButton(save, BoardTheme.TEXT_DIM);
		styleButton(saveAndSelect, BoardTheme.GOLD);
		save.addActionListener(event -> save(false));
		saveAndSelect.addActionListener(event -> save(true));
		panel.add(save);
		panel.add(saveAndSelect);
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
			model.addElement(new DeckChoice(deck, selectedId));
			if (deck.getId().equals(preferredId) || (preferredId == null && deck.getId().equals(selectedId)))
			{
				preferred = deck;
			}
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
		}
		else
		{
			newDeck();
		}
		rebuildCatalog();
	}

	private void updateOwnershipBanner()
	{
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		ownershipFilter.setEnabled(collection.isKnown());
		if (!collection.isKnown())
		{
			ownershipFilter.setSelectedIndex(0);
		}
		ownershipBanner.setText(BattleUiFormatters.collection(collection)
			+ (collection.isKnown() ? " - unowned cards are marked" : " - refresh the collection before checking ownership"));
		ownershipBanner.setBackground(collection.isKnown() ? new Color(48, 82, 58) : new Color(105, 78, 35));
	}

	private void loadDeck(Deck deck)
	{
		editingId = deck.getId();
		quantities.clear();
		quantities.putAll(BattleUiFormatters.quantities(deck));
		nameField.setText(deck.getName());
		updateDeckSummary();
		updateQuantityControls();
	}

	private void newDeck()
	{
		editingId = UUID.randomUUID().toString();
		quantities.clear();
		nameField.setText("New Deck");
		deckChooser.setSelectedItem(null);
		updateDeckSummary();
		updateQuantityControls();
		nameField.selectAll();
		nameField.requestFocusInWindow();
	}

	private void deleteDeck()
	{
		DeckChoice choice = (DeckChoice) deckChooser.getSelectedItem();
		if (choice == null || !choice.deck.getId().equals(editingId))
		{
			return;
		}
		if (JOptionPane.showConfirmDialog(frame, "Delete '" + choice.deck.getName() + "'?", "Delete deck",
			JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
		{
			controller.deleteDeck(choice.deck.getId());
			reloadProfile(null);
		}
	}

	private void selectDeck()
	{
		if (controller.getDeckProfile().getDecks().stream().anyMatch(deck -> deck.getId().equals(editingId)))
		{
			controller.selectDeck(editingId);
			reloadProfile(editingId);
		}
	}

	private void save(boolean select)
	{
		Deck deck = currentDeck();
		controller.saveDeck(deck, select);
		reloadProfile(deck.getId());
	}

	private Deck currentDeck()
	{
		String name = nameField.getText().trim();
		return new Deck(editingId, name.isEmpty() ? "Untitled Deck" : name,
			BattleUiFormatters.entries(quantities));
	}

	private void changeSelectedQuantity(int amount)
	{
		BattleCard card = catalogList.getSelectedValue();
		if (card == null)
		{
			return;
		}
		int next = Math.max(0, Math.min(controller.copyLimit(card),
			quantities.getOrDefault(card.getId(), 0) + amount));
		if (next == 0)
		{
			quantities.remove(card.getId());
		}
		else
		{
			quantities.put(card.getId(), next);
		}
		updateDeckSummary();
		updateQuantityControls();
		catalogList.repaint();
	}

	private void rebuildCatalog()
	{
		if (catalogModel == null)
		{
			return;
		}
		String selectedId = catalogList.getSelectedValue() == null ? null : catalogList.getSelectedValue().getId();
		String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
		int category = categoryFilter.getSelectedIndex();
		int owned = ownershipFilter.getSelectedIndex();
		OwnedCardCollectionSnapshot collection = controller.getCollection();
		List<BattleCard> cards = new ArrayList<>(controller.getCatalog().getCards());
		cards.sort(Comparator.comparing(BattleCard::getDisplayName, String.CASE_INSENSITIVE_ORDER));
		catalogModel.clear();
		for (BattleCard card : cards)
		{
			boolean isOwned = collection.owns(card.getAzCardName());
			boolean categoryMatches = category == 0 || (category == 1 && card.getCategory() == CardCategory.UNIT)
				|| (category == 2 && card.getCategory() == CardCategory.SPECIAL);
			boolean ownershipMatches = BattleUiFormatters.ownershipMatches(owned, collection.isKnown(), isOwned);
			String searchable = (card.getDisplayName() + " " + card.getRulesText()).toLowerCase(Locale.ROOT);
			if (categoryMatches && ownershipMatches && searchable.contains(query))
			{
				catalogModel.addElement(card);
				if (card.getId().equals(selectedId))
				{
					catalogList.setSelectedIndex(catalogModel.size() - 1);
				}
			}
		}
		updateQuantityControls();
	}

	private void updateDeckSummary()
	{
		if (deckModel == null || editingId == null)
		{
			return;
		}
		deckModel.clear();
		for (Map.Entry<String, Integer> entry : quantities.entrySet())
		{
			BattleCard card = controller.getCatalog().findById(entry.getKey()).orElse(null);
			deckModel.addElement(entry.getValue() + "x  " + (card == null ? entry.getKey() : card.getDisplayName())
				+ (card == null ? "" : "  [" + card.getManaCost() * entry.getValue() + " total mana]"));
		}
		int cards = BattleUiFormatters.cardCount(quantities);
		int totalManaCost = BattleUiFormatters.totalManaCost(controller.getCatalog(), quantities);
		totalsLabel.setText(cards + " cards  |  " + totalManaCost + " total mana cost");
		DeckValidationResult validation = controller.validate(currentDeck());
		boolean ownershipKnown = controller.getCollection().isKnown();
		DefaultListModel<String> errors = (DefaultListModel<String>) errorList.getModel();
		errors.clear();
		if (validation.isValid())
		{
			errors.addElement("Deck is valid");
		}
		else
		{
			for (DeckValidationError error : validation.getErrors())
			{
				if (ownershipKnown || error.getCode() != DeckValidationError.Code.UNOWNED_CARD)
				{
					errors.addElement(error.getMessage());
				}
			}
			if (errors.isEmpty())
			{
				errors.addElement("Ownership validation pending");
			}
		}
	}

	private void updateQuantityControls()
	{
		BattleCard card = catalogList == null ? null : catalogList.getSelectedValue();
		int quantity = card == null ? 0 : quantities.getOrDefault(card.getId(), 0);
		int copyLimit = card == null ? 0 : controller.copyLimit(card);
		selectedQuantityLabel.setText(quantity + " / " + copyLimit);
		removeButton.setEnabled(card != null && quantity > 0);
		addButton.setEnabled(card != null && quantity < copyLimit);
	}

	private BattleCard deckCardAt(int index)
	{
		if (index < 0 || index >= quantities.size())
		{
			return null;
		}
		String id = new ArrayList<>(quantities.keySet()).get(index);
		return controller.getCatalog().findById(id).orElse(null);
	}

	private void selectChoice(String id)
	{
		for (int i = 0; i < deckChooser.getItemCount(); i++)
		{
			if (deckChooser.getItemAt(i).deck.getId().equals(id))
			{
				deckChooser.setSelectedIndex(i);
				return;
			}
		}
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
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while creating deck builder", ex);
		}
		catch (InvocationTargetException ex)
		{
			throw new IllegalStateException("Could not create deck builder", ex.getCause());
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
			boolean owned = collection.owns(card.getAzCardName());
			String marker = BattleUiFormatters.ownershipMarker(collection.isKnown(), owned);
			String text = "<html><b>" + card.getDisplayName() + "</b>  <font color='#d6b85f'>"
				+ card.getManaCost() + " mana</font>  [" + marker + "]"
				+ (count == 0 ? "" : "  x" + count) + "<br><font color='#a89c88'>"
				+ card.getRulesText() + "</font></html>";
			Component component = super.getListCellRendererComponent(list, text, index, selected, focus);
			if (component instanceof JLabel)
			{
				((JLabel) component).setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
			}
			return component;
		}
	}

	private static void styleSection(JPanel panel, String title)
	{
		panel.setBackground(BoardTheme.BACKGROUND);
		panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BoardTheme.TEXT_DIM.darker()),
			title, 0, 0, null, BoardTheme.GOLD));
	}

	private static void styleButton(JButton button, Color accent)
	{
		button.setForeground(BoardTheme.TEXT);
		button.setBackground(BoardTheme.ROW_BAND_LIGHT);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent.darker()),
			BorderFactory.createEmptyBorder(3, 7, 3, 7)));
	}

	private static void styleInput(JTextField field)
	{
		field.setBackground(BoardTheme.ROW_BAND);
		field.setForeground(BoardTheme.TEXT);
		field.setCaretColor(BoardTheme.GOLD);
	}

	private static final class DeckChoice
	{
		private final Deck deck;
		private final String label;

		private DeckChoice(Deck deck, String selectedId)
		{
			this.deck = deck;
			this.label = BattleUiFormatters.deckChoice(deck, selectedId);
		}

		@Override
		public String toString()
		{
			return label;
		}
	}
}
