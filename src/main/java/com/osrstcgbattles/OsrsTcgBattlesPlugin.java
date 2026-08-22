package com.osrstcgbattles;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrstcgbattles.art.CardArtCatalog;
import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.art.NoCardArtProvider;
import com.osrstcgbattles.art.RuneLiteCardArtProvider;
import com.osrstcgbattles.art.SharedNpcImageCache;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.collection.OwnedCardCollectionBridge;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckValidationResult;
import com.osrstcgbattles.deck.DeckValidator;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.integration.CatalogCardLookup;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.integration.StarterDeckFactory;
import com.osrstcgbattles.match.PartyMatchCoordinator;
import com.osrstcgbattles.persist.DeckProfile;
import com.osrstcgbattles.persist.DeckProfileCodec;
import com.osrstcgbattles.persist.DeckProfileRepository;
import com.osrstcgbattles.party.BattlePartyListener;
import com.osrstcgbattles.party.BattlePartyService;
import com.osrstcgbattles.party.DeckCommitment;
import com.osrstcgbattles.party.PartyApplicationMessage;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import com.osrstcgbattles.party.PartyOpponent;
import com.osrstcgbattles.ui.BattleUiController;
import com.osrstcgbattles.ui.DeckBuilderWindow;
import com.osrstcgbattles.ui.DeckReadiness;
import com.osrstcgbattles.ui.LocalBattleWindow;
import com.osrstcgbattles.ui.OsrsTcgBattlesPanel;
import com.osrstcgbattles.ui.PartyBattleWindow;
import com.osrstcgbattles.ui.PartyBattleParticipant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.PartyMemberAvatar;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

@PluginDescriptor(
	name = "Duelscape TCG",
	description = "Build decks for Duelscape TCG and play local or synchronized friend battles",
	tags = {"cards", "tcg", "battle"}
)
public class OsrsTcgBattlesPlugin extends Plugin implements BattleUiController
{
	private static final int OWNERSHIP_RETRY_TICKS = 100;
	private static final int PARTY_UI_REFRESH_TICKS = 10;
	private static final long DEMO_SEED = 0x4f535253544347L;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OwnedCardCollectionBridge collectionBridge;

	@Inject
	private BattlePartyService battlePartyService;

	@Inject
	private PartyService partyService;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	private BattleCardCatalog catalog;
	private CatalogCardLookup cardLookup;
	private DeckValidator deckValidator;
	private StarterDeckFactory starterDeckFactory;
	private DeckProfileCodec profileCodec;
	private DeckProfileRepository profileRepository;
	private OsrsTcgBattlesPanel panel;
	private NavigationButton navigationButton;
	private DeckBuilderWindow deckBuilderWindow;
	private LocalBattleWindow localBattleWindow;
	private PartyMatchCoordinator partyMatchCoordinator;
	private PartyBattleWindow partyBattleWindow;
	private SharedNpcImageCache npcImageCache;
	private Deck pendingPartyDeck;
	private final SecureRandom secureRandom = new SecureRandom();
	private int unknownOwnershipTicks;
	private int partyUiRefreshTicks;
	private final Object stateLock = new Object();
	private boolean running;
	private boolean startingUp;
	private long lifecycleGeneration;
	private long partySessionGeneration;
	private String partyMatchId;
	private BattlePartyListener partyListener;
	private String partyUserMessage;
	private boolean partySubmissionPending;
	private final OwnedCardCollectionBridge.Listener collectionListener = snapshot -> refreshUi();

	@Provides
	OsrsTcgBattlesConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(OsrsTcgBattlesConfig.class);
	}

	@Override
	protected void startUp()
	{
		long generation;
		SharedNpcImageCache cache;
		synchronized (stateLock)
		{
			catalog = new BattleCardCatalogLoader(gson).loadDefault();
			starterDeckFactory = new StarterDeckFactory(catalog);
			cardLookup = new CatalogCardLookup(catalog);
			deckValidator = new DeckValidator();
			profileCodec = new DeckProfileCodec(gson);
			profileRepository = loadProfileRepository();
			npcImageCache = new SharedNpcImageCache(httpClient);
			cache = npcImageCache;
			running = true;
			startingUp = true;
			generation = ++lifecycleGeneration;
		}
		// Started immediately after construction, with no early-return path in between: every check
		// further down this method (isCurrent(generation)) can make startUp() return early if a
		// concurrent onRuneScapeProfileChanged() bumps lifecycleGeneration while this method is still
		// running (e.g. during the createPanelOnEdt() round trip below), but the cache must never be
		// left constructed-and-unstarted when that happens. SharedNpcImageCache.get() adds every
		// caller to a per-URL pending list before checking whether a fetch pool exists; if start() is
		// skipped, that list is never drained, so every consumer that ever called get() on this
		// instance leaks and that card's art is stuck on the emblem placeholder for the rest of the
		// plugin's life. start()/dispose() are synchronized methods on the cache and must never run
		// while this plugin holds stateLock (see OsrsTcgBattlesPluginLockScopeTest's discipline for
		// collectionBridge, which the same deadlock risk applies to here), so this call is
		// deliberately outside the block above.
		cache.start();

		OsrsTcgBattlesPanel newPanel = createPanelOnEdt(generation);
		synchronized (stateLock)
		{
			if (!isCurrent(generation))
			{
				return;
			}
			panel = newPanel;
			navigationButton = NavigationButton.builder()
				.tooltip("Duelscape TCG")
				.icon(createIcon())
				.priority(7)
				.panel(newPanel)
				.build();
			clientToolbar.addNavigation(navigationButton);
		}

		collectionBridge.addListener(collectionListener);
		collectionBridge.start();
		BattlePartyListener listener = createPartyListener(generation);
		synchronized (stateLock)
		{
			if (!isCurrent(generation))
			{
				return;
			}
			partyListener = listener;
		}
		battlePartyService.addListener(listener);
		battlePartyService.start();
		synchronized (stateLock)
		{
			if (isCurrent(generation)) startingUp = false;
		}
		refreshUi(generation);
	}

	@Override
	protected void shutDown()
	{
		NavigationButton oldNavigation;
		DeckBuilderWindow builder;
		LocalBattleWindow battle;
		OsrsTcgBattlesPanel oldPanel;
		BattlePartyListener oldPartyListener;
		SharedNpcImageCache oldNpcImageCache;
		long shutdownGeneration;
		synchronized (stateLock)
		{
			running = false;
			startingUp = false;
			shutdownGeneration = ++lifecycleGeneration;
			partySessionGeneration++;
			oldNavigation = navigationButton;
			builder = deckBuilderWindow;
			battle = localBattleWindow;
			oldPanel = panel;
			oldPartyListener = partyListener;
			oldNpcImageCache = npcImageCache;
			navigationButton = null;
			deckBuilderWindow = null;
			localBattleWindow = null;
			panel = null;
			partyListener = null;
			npcImageCache = null;
			pendingPartyDeck = null;
			profileRepository = null;
			unknownOwnershipTicks = 0;
			partyUiRefreshTicks = 0;
			partyUserMessage = null;
			partySubmissionPending = false;
		}

		battlePartyService.removeListener(oldPartyListener);
		battlePartyService.stop();
		collectionBridge.removeListener(collectionListener);
		collectionBridge.stop();
		if (oldNpcImageCache != null)
		{
			oldNpcImageCache.dispose();
		}
		if (oldNavigation != null)
		{
			clientToolbar.removeNavigation(oldNavigation);
		}
		runOnEdtAndWait(() -> {
			clearPartyMatchOnEdt(shutdownGeneration, false, true);
			if (builder != null)
			{
				builder.dispose();
			}
			if (battle != null)
			{
				battle.dispose();
			}
			if (oldPanel != null)
			{
				oldPanel.reset();
			}
		});
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		boolean abortTransport = battlePartyService.getSnapshot().canAbort();
		boolean startupRefresh;
		long generation;
		BattlePartyListener oldPartyListener;
		BattlePartyListener newPartyListener;
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			startupRefresh = startingUp;
			generation = startupRefresh ? lifecycleGeneration : ++lifecycleGeneration;
			partySessionGeneration++;
			oldPartyListener = startupRefresh ? null : partyListener;
			newPartyListener = startupRefresh ? null : createPartyListener(generation);
			if (!startupRefresh) partyListener = newPartyListener;
			pendingPartyDeck = null;
			profileRepository = loadProfileRepository();
			unknownOwnershipTicks = 0;
			partyUserMessage = null;
			partySubmissionPending = false;
		}
		// Both calls dispatch to other plugins' subscribers, so they must run outside stateLock.
		collectionBridge.invalidate();
		collectionBridge.queryNow();
		if (abortTransport) battlePartyService.abort();
		if (startupRefresh)
		{
			return;
		}
		runOnEdtAndWait(() -> {
			clearPartyMatchOnEdt(generation, true, true);
			DeckBuilderWindow builder;
			LocalBattleWindow battle;
			synchronized (stateLock)
			{
				if (!isCurrent(generation)) return;
				builder = deckBuilderWindow;
				battle = localBattleWindow;
				deckBuilderWindow = null;
				localBattleWindow = null;
			}
			if (builder != null) builder.dispose();
			if (battle != null) battle.dispose();
		});
		battlePartyService.removeListener(oldPartyListener);
		battlePartyService.addListener(newPartyListener);
		refreshUi(generation);
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		boolean queryOwnership;
		boolean invalidateOwnership;
		synchronized (stateLock)
		{
			boolean collectionPlugin = running && event.getPlugin() != null
				&& "OSRS TCG".equals(event.getPlugin().getName());
			queryOwnership = collectionPlugin && event.isLoaded();
			invalidateOwnership = collectionPlugin && !event.isLoaded();
			if (queryOwnership || invalidateOwnership)
			{
				unknownOwnershipTicks = 0;
			}
		}
		if (invalidateOwnership)
		{
			collectionBridge.invalidate();
			refreshUi();
		}
		if (queryOwnership)
		{
			collectionBridge.queryNow();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		boolean refreshPartyUi = false;
		boolean queryOwnership = false;
		PartyMatchCoordinator matchCoordinator;
		long generation;
		long sessionGeneration;
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			if (++partyUiRefreshTicks >= PARTY_UI_REFRESH_TICKS)
			{
				partyUiRefreshTicks = 0;
				refreshPartyUi = true;
			}
			if (collectionBridge.snapshot().isKnown())
			{
				unknownOwnershipTicks = 0;
			}
			else if (++unknownOwnershipTicks >= OWNERSHIP_RETRY_TICKS)
			{
				unknownOwnershipTicks = 0;
				queryOwnership = true;
			}
			matchCoordinator = partyMatchCoordinator;
			generation = lifecycleGeneration;
			sessionGeneration = partySessionGeneration;
		}
		if (matchCoordinator != null)
		{
			SwingUtilities.invokeLater(() -> {
				synchronized (stateLock)
				{
					if (!isPartySessionCurrent(generation, sessionGeneration)
						|| partyMatchCoordinator != matchCoordinator) return;
				}
				matchCoordinator.onTick();
			});
		}
		if (queryOwnership)
		{
			collectionBridge.queryNow();
		}
		if (refreshPartyUi)
		{
			refreshUi();
		}
	}

	@Subscribe
	public void onPartyMemberAvatar(PartyMemberAvatar event)
	{
		PartyBattleWindow window;
		long generation;
		long sessionGeneration;
		synchronized (stateLock)
		{
			window = partyBattleWindow;
			if (!running || window == null || !window.hasParticipant(event.getMemberId())) return;
			generation = lifecycleGeneration;
			sessionGeneration = partySessionGeneration;
		}
		SwingUtilities.invokeLater(() -> {
			synchronized (stateLock)
			{
				if (!running || lifecycleGeneration != generation
					|| partySessionGeneration != sessionGeneration || partyBattleWindow != window) return;
			}
			window.updateAvatar(event.getMemberId(), event.getImage());
		});
	}

	@Override
	public BattleCardCatalog getCatalog()
	{
		synchronized (stateLock)
		{
			return catalog;
		}
	}

	@Override
	public OwnedCardCollectionSnapshot getCollection()
	{
		return collectionBridge.snapshot();
	}

	@Override
	public DeckProfile getDeckProfile()
	{
		synchronized (stateLock)
		{
			return profileRepository == null ? DeckProfile.empty() : profileRepository.getCurrent();
		}
	}

	@Override
	public DeckValidationResult validate(Deck deck)
	{
		OwnedCardCollectionSnapshot snapshot = collectionBridge.snapshot();
		synchronized (stateLock)
		{
			boolean builtIn = starterDeckFactory != null && starterDeckFactory.isUnmodifiedStarter(deck);
			return deckValidator.validate(deck, cardLookup, snapshot.getOwnedNames(),
				builtIn ? false : snapshot.isKnown());
		}
	}

	@Override
	public DeckReadiness getDeckReadiness(Deck deck)
	{
		OwnedCardCollectionSnapshot snapshot = collectionBridge.snapshot();
		synchronized (stateLock)
		{
			boolean builtIn = starterDeckFactory != null && starterDeckFactory.isUnmodifiedStarter(deck);
			DeckValidationResult validation = deckValidator.validate(deck, cardLookup, snapshot.getOwnedNames(),
				builtIn ? false : snapshot.isKnown());
			DeckReadiness.Status status;
			if (builtIn && validation.isValid())
			{
				status = DeckReadiness.Status.BUILT_IN_STARTER;
			}
			else if (!validation.isValid())
			{
				status = DeckReadiness.Status.INVALID;
			}
			else if (!snapshot.isKnown())
			{
				status = DeckReadiness.Status.OWNERSHIP_PENDING;
			}
			else
			{
				status = DeckReadiness.Status.READY;
			}
			return new DeckReadiness(status, validation);
		}
	}

	@Override
	public List<Deck> getStarterDecks()
	{
		synchronized (stateLock)
		{
			return starterDeckFactory == null ? Collections.emptyList() : starterDeckFactory.createStarterDecks();
		}
	}

	@Override
	public boolean isStarterId(String deckId)
	{
		synchronized (stateLock)
		{
			return starterDeckFactory != null && starterDeckFactory.isStarterId(deckId);
		}
	}

	@Override
	public int copyLimit(BattleCard card)
	{
		synchronized (stateLock)
		{
			return cardLookup.findById(card.getId())
				.map(DeckValidator::copyLimit)
				.orElse(DeckValidator.MAX_STANDARD_COPIES);
		}
	}

	@Override
	public void saveDeck(Deck deck, boolean select)
	{
		long generation;
		synchronized (stateLock)
		{
			generation = lifecycleGeneration;
		}
		saveDeck(deck, select, generation);
	}

	private void saveDeck(Deck deck, boolean select, long generation)
	{
		synchronized (stateLock)
		{
			if (!isCurrent(generation) || profileRepository == null)
			{
				return;
			}
			DeckProfileRepository repository = profileRepository;
			DeckProfile current = repository.getCurrent();
			Deck persisted = current.getDecks().stream().filter(value -> value.getId().equals(deck.getId()))
				.findFirst().orElse(null);
			if (persisted != null && starterDeckFactory.isUnmodifiedStarter(persisted)
				&& !starterDeckFactory.isUnmodifiedStarter(deck))
			{
				return;
			}
			List<Deck> decks = new ArrayList<>(current.getDecks());
			int existing = -1;
			for (int i = 0; i < decks.size(); i++)
			{
				if (decks.get(i).getId().equals(deck.getId()))
				{
					existing = i;
					break;
				}
			}
			if (existing >= 0)
			{
				decks.set(existing, deck);
			}
			else
			{
				decks.add(deck);
			}
			String selectedId = select ? deck.getId() : current.getSelectedDeckId().orElse(null);
			repository.save(new DeckProfile(decks, selectedId));
		}
		refreshUi(generation);
	}

	@Override
	public void deleteDeck(String deckId)
	{
		long generation;
		synchronized (stateLock)
		{
			generation = lifecycleGeneration;
		}
		deleteDeck(deckId, generation);
	}

	private void deleteDeck(String deckId, long generation)
	{
		if (deckId == null)
		{
			return;
		}
		synchronized (stateLock)
		{
			if (!isCurrent(generation) || profileRepository == null)
			{
				return;
			}
			DeckProfileRepository repository = profileRepository;
			if (starterDeckFactory != null && starterDeckFactory.isStarterId(deckId))
			{
				return;
			}
			DeckProfile current = repository.getCurrent();
			List<Deck> decks = new ArrayList<>(current.getDecks());
			if (!decks.removeIf(deck -> deck.getId().equals(deckId)))
			{
				return;
			}
			String selectedId = current.getSelectedDeckId().filter(id -> !id.equals(deckId)).orElse(null);
			repository.save(new DeckProfile(decks, selectedId));
		}
		refreshUi(generation);
	}

	@Override
	public void selectDeck(String deckId)
	{
		long generation;
		synchronized (stateLock)
		{
			generation = lifecycleGeneration;
		}
		selectDeck(deckId, generation);
	}

	private void selectDeck(String deckId, long generation)
	{
		if (deckId == null)
		{
			return;
		}
		synchronized (stateLock)
		{
			if (!isCurrent(generation) || profileRepository == null)
			{
				return;
			}
			DeckProfileRepository repository = profileRepository;
			DeckProfile current = repository.getCurrent();
			if (current.getDecks().stream().noneMatch(deck -> deck.getId().equals(deckId)))
			{
				return;
			}
			repository.save(new DeckProfile(current.getDecks(), deckId));
		}
		refreshUi(generation);
	}

	@Override
	public void refreshCollection()
	{
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			unknownOwnershipTicks = 0;
		}
		collectionBridge.queryNow();
	}

	@Override
	public void openDeckBuilder()
	{
		long generation;
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			generation = lifecycleGeneration;
		}
		SwingUtilities.invokeLater(() -> {
			synchronized (stateLock)
			{
				if (!isCurrent(generation))
				{
					return;
				}
				if (deckBuilderWindow == null)
				{
					deckBuilderWindow = new DeckBuilderWindow(new EditorController(generation));
				}
				deckBuilderWindow.showWindow();
			}
		});
	}

	@Override
	public void startDemoMatch()
	{
		long generation;
		BattleCardCatalog currentCatalog;
		Deck selected;
		List<Deck> starters;
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			generation = lifecycleGeneration;
			currentCatalog = catalog;
			DeckProfile profile = profileRepository == null ? DeckProfile.empty() : profileRepository.getCurrent();
			selected = profile.getSelectedDeckId().flatMap(id -> profile.getDecks().stream()
				.filter(deck -> deck.getId().equals(id)).findFirst()).orElse(null);
			starters = starterDeckFactory == null ? Collections.emptyList() : starterDeckFactory.createStarterDecks();
		}
		if (selected == null || !getDeckReadiness(selected).isPlayable())
		{
			JOptionPane.showMessageDialog(null, "Select a ready deck before starting a local battle.",
				"Local Battle", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String[] choices = starters.stream().map(Deck::getName).toArray(String[]::new);
		String opponentName = (String) JOptionPane.showInputDialog(null, "Choose Player 2's deck:",
			"Local Battle", JOptionPane.PLAIN_MESSAGE, null, choices, choices.length == 0 ? null : choices[0]);
		if (opponentName == null) return;
		Deck opponent = starters.stream().filter(deck -> deck.getName().equals(opponentName)).findFirst().orElse(null);
		if (opponent == null) return;
		CatalogDeckFactory factory = new CatalogDeckFactory(currentCatalog);
		List<Card> firstDeck = factory.create(selected);
		List<Card> secondDeck = factory.create(opponent);
		DuelscapeEngine engine = new DuelscapeEngine();
		MatchState match = engine.newMatchWithMulligan(firstDeck, secondDeck, DEMO_SEED);
		SwingUtilities.invokeLater(() -> {
			synchronized (stateLock)
			{
				if (!isCurrent(generation))
				{
					return;
				}
				if (localBattleWindow != null)
				{
					localBattleWindow.dispose();
				}
				localBattleWindow = new LocalBattleWindow(engine, match, currentCatalog, cardArtProvider());
				localBattleWindow.showWindow();
			}
		});
	}

	/**
	 * Art is cosmetic: if a provider cannot be built (cache never started, catalog resource
	 * missing, etc.) fall back to {@link NoCardArtProvider} rather than letting a demo match fail
	 * to open.
	 */
	private CardArtProvider cardArtProvider()
	{
		SharedNpcImageCache cache;
		synchronized (stateLock)
		{
			cache = npcImageCache;
		}
		if (cache == null)
		{
			return new NoCardArtProvider();
		}
		try
		{
			return new RuneLiteCardArtProvider(itemManager, cache, new CardArtCatalog());
		}
		catch (RuntimeException exception)
		{
			return new NoCardArtProvider();
		}
	}

	@Override
	public PartyDuelSnapshot getPartyDuelSnapshot()
	{
		synchronized (stateLock)
		{
			return running ? battlePartyService.getSnapshot() : PartyDuelSnapshot.idle();
		}
	}

	@Override
	public List<PartyOpponent> getPartyOpponents()
	{
		synchronized (stateLock)
		{
			return running ? Collections.unmodifiableList(new ArrayList<>(battlePartyService.getEligibleOpponents()))
				: Collections.emptyList();
		}
	}

	@Override
	public String getPartyDuelMessage()
	{
		synchronized (stateLock)
		{
			return partyUserMessage;
		}
	}

	@Override
	public void invitePartyOpponent(long memberId)
	{
		long generation = currentGeneration();
		if (generation < 0)
		{
			return;
		}
		boolean eligible = getPartyOpponents().stream().anyMatch(opponent -> opponent.getMemberId() == memberId);
		if (!eligible)
		{
			showPartyMessage(generation, "Select an eligible RuneLite party opponent");
			return;
		}
		if (!beginPartySubmission(generation))
		{
			showPartyMessage(generation, "A party duel request is already being submitted");
			return;
		}
		PartyDeckMetadata metadata = preparePartyDeck(generation);
		if (metadata == null)
		{
			endPartySubmission(generation);
			return;
		}
		try
		{
			if (!battlePartyService.invite(memberId, metadata.catalogHash, metadata.rulesetVersion,
				metadata.deckId, metadata.commitment))
			{
				clearPendingPartyDeck(generation, metadata.sessionGeneration);
				endPartySubmission(generation);
				showPartyMessage(generation, "A party duel request is already being submitted");
			}
		}
		catch (RuntimeException exception)
		{
			clearPendingPartyDeck(generation, metadata.sessionGeneration);
			endPartySubmission(generation);
			showPartyMessage(generation, "Unable to send the party duel invitation");
		}
	}

	@Override
	public void acceptPartyDuel()
	{
		long generation = currentGeneration();
		if (generation < 0 || !battlePartyService.getSnapshot().canAccept())
		{
			return;
		}
		if (!beginPartySubmission(generation))
		{
			showPartyMessage(generation, "A party duel request is already being submitted");
			return;
		}
		PartyDeckMetadata metadata = preparePartyDeck(generation);
		if (metadata == null)
		{
			endPartySubmission(generation);
			return;
		}
		try
		{
			if (!battlePartyService.accept(metadata.catalogHash, metadata.rulesetVersion,
				metadata.deckId, metadata.commitment))
			{
				clearPendingPartyDeck(generation, metadata.sessionGeneration);
				endPartySubmission(generation);
				showPartyMessage(generation, "A party duel request is already being submitted");
			}
		}
		catch (RuntimeException exception)
		{
			clearPendingPartyDeck(generation, metadata.sessionGeneration);
			endPartySubmission(generation);
			showPartyMessage(generation, "Unable to accept the party duel invitation");
		}
	}

	@Override
	public void declinePartyDuel()
	{
		long generation = currentGeneration();
		if (generation >= 0 && battlePartyService.getSnapshot().canDecline())
		{
			battlePartyService.decline();
			showPartyMessage(generation, null);
		}
	}

	@Override
	public void abortPartyDuel()
	{
		long generation = currentGeneration();
		if (generation >= 0)
		{
			long sessionGeneration;
			synchronized (stateLock)
			{
				if (!isCurrent(generation)) return;
				sessionGeneration = ++partySessionGeneration;
				pendingPartyDeck = null;
				partySubmissionPending = false;
			}
			queuePartyMatchClear(generation, sessionGeneration, true);
			if (battlePartyService.getSnapshot().canAbort()) battlePartyService.abort();
			showPartyMessage(generation, null);
		}
	}

	private long currentGeneration()
	{
		synchronized (stateLock)
		{
			return running ? lifecycleGeneration : -1;
		}
	}

	private boolean beginPartySubmission(long generation)
	{
		synchronized (stateLock)
		{
			if (!isCurrent(generation) || partySubmissionPending) return false;
			partySubmissionPending = true;
			return true;
		}
	}

	private void endPartySubmission(long generation)
	{
		synchronized (stateLock)
		{
			if (isCurrent(generation)) partySubmissionPending = false;
		}
	}

	private PartyDeckMetadata preparePartyDeck(long generation)
	{
		Deck selected;
		BattleCardCatalog currentCatalog;
		synchronized (stateLock)
		{
			if (!isCurrent(generation) || profileRepository == null)
			{
				return null;
			}
			DeckProfile profile = profileRepository.getCurrent();
			selected = profile.getSelectedDeckId().flatMap(id -> profile.getDecks().stream()
				.filter(deck -> deck.getId().equals(id)).findFirst()).orElse(null);
			currentCatalog = catalog;
			partyUserMessage = null;
		}
		if (currentCatalog == null)
		{
			showPartyMessage(generation, "The card catalog is not loaded");
			return null;
		}
		if (selected == null || !getDeckReadiness(selected).isPlayable())
		{
			showPartyMessage(generation, "Select a ready deck before starting a friend duel");
			return null;
		}
		Deck duelDeck = selected;
		try
		{
			String commitment = DeckCommitment.compute(currentCatalog.getSha256(),
				currentCatalog.getRulesetVersion(), duelDeck);
			runOnEdtAndWait(() -> clearStalePartyMatchOnEdt(generation));
			long sessionGeneration;
			synchronized (stateLock)
			{
				if (!isCurrent(generation)) return null;
				pendingPartyDeck = duelDeck;
				sessionGeneration = ++partySessionGeneration;
			}
			return new PartyDeckMetadata(currentCatalog.getSha256(), currentCatalog.getRulesetVersion(),
				duelDeck.getId(), commitment, sessionGeneration);
		}
		catch (RuntimeException exception)
		{
			showPartyMessage(generation, "The selected deck cannot be used for a friend duel");
			return null;
		}
	}

	private void clearPendingPartyDeck(long generation, long sessionGeneration)
	{
		synchronized (stateLock)
		{
			if (isCurrent(generation) && partySessionGeneration == sessionGeneration)
			{
				pendingPartyDeck = null;
			}
		}
	}

	private void showPartyMessage(long generation, String message)
	{
		synchronized (stateLock)
		{
			if (!isCurrent(generation))
			{
				return;
			}
			partyUserMessage = message;
		}
		refreshUi(generation);
	}

	private BattlePartyListener createPartyListener(long generation)
	{
		return new BattlePartyListener()
		{
			@Override
			public void onPartyDuelSnapshotChanged(PartyDuelSnapshot snapshot)
			{
				long sessionGeneration;
				long localMemberId = battlePartyService.getLocalMemberId();
				Deck frozenDeck;
				BattleCardCatalog currentCatalog;
				DeckValidator currentValidator;
				CatalogCardLookup currentLookup;
				synchronized (stateLock)
				{
					if (!isCurrent(generation) || partyListener != this) return;
					sessionGeneration = partySessionGeneration;
					frozenDeck = pendingPartyDeck;
					currentCatalog = catalog;
					currentValidator = deckValidator;
					currentLookup = cardLookup;
					partySubmissionPending = false;
				}
				SwingUtilities.invokeLater(() -> handlePartySnapshotOnEdt(generation, sessionGeneration,
					snapshot, localMemberId, frozenDeck, currentCatalog, currentValidator, currentLookup));
				refreshUi(generation);
			}

			@Override
			public void onPartyApplicationMessage(PartyApplicationMessage message)
			{
				long sessionGeneration;
				synchronized (stateLock)
				{
					if (!isCurrent(generation) || partyListener != this) return;
					sessionGeneration = partySessionGeneration;
				}
				SwingUtilities.invokeLater(() -> receivePartyApplicationOnEdt(
					generation, sessionGeneration, message));
			}

			@Override
			public void onPartyOperationFailed(String message)
			{
				synchronized (stateLock)
				{
					if (!isCurrent(generation) || partyListener != this) return;
					pendingPartyDeck = null;
					partySubmissionPending = false;
					partySessionGeneration++;
					partyUserMessage = message;
				}
				refreshUi(generation);
			}
		};
	}

	private void handlePartySnapshotOnEdt(long generation, long sessionGeneration, PartyDuelSnapshot snapshot,
		long localMemberId, Deck frozenDeck, BattleCardCatalog currentCatalog, DeckValidator currentValidator,
		CatalogCardLookup currentLookup)
	{
		if (!isPartySessionCurrent(generation, sessionGeneration)) return;
		if (snapshot.getStatus() == PartyDuelSnapshot.Status.READY)
		{
			startPartyMatchOnEdt(generation, sessionGeneration, snapshot, localMemberId, frozenDeck,
				currentCatalog, currentValidator, currentLookup);
		}
		else if (snapshot.getStatus() == PartyDuelSnapshot.Status.TERMINAL)
		{
			PartyMatchCoordinator coordinator;
			synchronized (stateLock)
			{
				if (!isPartySessionCurrent(generation, sessionGeneration)) return;
				pendingPartyDeck = null;
				coordinator = partyMatchCoordinator;
			}
			if (coordinator != null) coordinator.abort();
		}
	}

	private void startPartyMatchOnEdt(long generation, long sessionGeneration, PartyDuelSnapshot snapshot,
		long localMemberId, Deck frozenDeck, BattleCardCatalog currentCatalog, DeckValidator currentValidator,
		CatalogCardLookup currentLookup)
	{
		synchronized (stateLock)
		{
			if (!isPartySessionCurrent(generation, sessionGeneration)) return;
			if (snapshot.getMatchId() != null && snapshot.getMatchId().equals(partyMatchId)) return;
		}
		boolean validDeck = frozenDeck != null && localMemberId > 0
			&& frozenDeck.getId().equals(snapshot.getDeckId());
		try
		{
			validDeck = validDeck && DeckCommitment.compute(currentCatalog.getSha256(),
				currentCatalog.getRulesetVersion(), frozenDeck).equals(snapshot.getDeckCommitment());
		}
		catch (RuntimeException exception)
		{
			validDeck = false;
		}
		if (!validDeck)
		{
			failPartyMatchSetup(generation, sessionGeneration,
				"The frozen deck no longer matches this friend duel");
			return;
		}

		PartyMatchCoordinator coordinator;
		PartyBattleWindow window;
		try
		{
			PartyMember localMember = partyService.getMemberById(localMemberId);
			PartyMember opponentMember = partyService.getMemberById(snapshot.getPeerMemberId());
			PartyBattleParticipant localParticipant = new PartyBattleParticipant(localMemberId,
				localMember == null ? null : localMember.getDisplayName(), null,
				localMember == null ? null : localMember.getAvatar());
			PartyBattleParticipant opponentParticipant = new PartyBattleParticipant(snapshot.getPeerMemberId(),
				opponentMember == null ? null : opponentMember.getDisplayName(), snapshot.getPeerDisplayName(),
				opponentMember == null ? null : opponentMember.getAvatar());
			coordinator = new PartyMatchCoordinator(localMemberId, snapshot, currentCatalog, currentValidator,
				currentLookup, new CatalogDeckFactory(currentCatalog), frozenDeck,
				battlePartyService::queueApplication, secureRandom.nextLong());
			PartyMatchCoordinator createdCoordinator = coordinator;
			window = new PartyBattleWindow(coordinator, currentCatalog,
				() -> abortPartyMatchFromWindow(generation, sessionGeneration, createdCoordinator), cardArtProvider(),
				localParticipant, opponentParticipant);
		}
		catch (RuntimeException exception)
		{
			failPartyMatchSetup(generation, sessionGeneration, "Unable to start the synchronized friend duel");
			return;
		}

		boolean installed;
		synchronized (stateLock)
		{
			installed = isPartySessionCurrent(generation, sessionGeneration) && pendingPartyDeck == frozenDeck;
			if (installed)
			{
				partyMatchCoordinator = coordinator;
				partyBattleWindow = window;
				partyMatchId = snapshot.getMatchId();
			}
		}
		if (!installed)
		{
			coordinator.abort();
			window.dispose();
			return;
		}
		// Close the small gap where an avatar event could arrive while the window was being built,
		// before partyBattleWindow was installed for the event subscriber to find.
		PartyMember refreshedLocal = partyService.getMemberById(localMemberId);
		PartyMember refreshedOpponent = partyService.getMemberById(snapshot.getPeerMemberId());
		if (refreshedLocal != null && refreshedLocal.getAvatar() != null)
		{
			window.updateAvatar(localMemberId, refreshedLocal.getAvatar());
		}
		if (refreshedOpponent != null && refreshedOpponent.getAvatar() != null)
		{
			window.updateAvatar(snapshot.getPeerMemberId(), refreshedOpponent.getAvatar());
		}
		window.showWindow();
		coordinator.start();
	}

	private void receivePartyApplicationOnEdt(long generation, long sessionGeneration,
		PartyApplicationMessage message)
	{
		PartyMatchCoordinator coordinator;
		synchronized (stateLock)
		{
			if (!isPartySessionCurrent(generation, sessionGeneration)
				|| !message.getMatchId().equals(partyMatchId)) return;
			coordinator = partyMatchCoordinator;
		}
		if (coordinator != null) coordinator.receive(message);
	}

	private void failPartyMatchSetup(long generation, long sessionGeneration, String message)
	{
		synchronized (stateLock)
		{
			if (!isPartySessionCurrent(generation, sessionGeneration)) return;
			pendingPartyDeck = null;
			partyUserMessage = message;
		}
		battlePartyService.abort();
		refreshUi(generation);
	}

	private void abortPartyMatchFromWindow(long generation, long sessionGeneration,
		PartyMatchCoordinator coordinator)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> abortPartyMatchFromWindow(generation, sessionGeneration, coordinator));
			return;
		}
		synchronized (stateLock)
		{
			if (!isPartySessionCurrent(generation, sessionGeneration)
				|| partyMatchCoordinator != coordinator) return;
			pendingPartyDeck = null;
			partyMatchCoordinator = null;
			partyBattleWindow = null;
			partyMatchId = null;
			partySessionGeneration++;
		}
		coordinator.abort();
		if (battlePartyService.getSnapshot().canAbort()) battlePartyService.abort();
	}

	private void queuePartyMatchClear(long generation, long sessionGeneration, boolean abort)
	{
		SwingUtilities.invokeLater(() -> {
			if (!isPartySessionCurrent(generation, sessionGeneration)) return;
			clearPartyMatchOnEdt(generation, true, abort);
		});
	}

	private void clearStalePartyMatchOnEdt(long generation)
	{
		if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Party match is not on the EDT");
		PartyMatchCoordinator coordinator;
		PartyBattleWindow window;
		synchronized (stateLock)
		{
			if (!isCurrent(generation)) return;
			coordinator = partyMatchCoordinator;
			window = partyBattleWindow;
			partyMatchCoordinator = null;
			partyBattleWindow = null;
			partyMatchId = null;
		}
		if (coordinator != null) coordinator.abort();
		if (window != null) window.dispose();
	}

	private void clearPartyMatchOnEdt(long generation, boolean requireRunning, boolean abort)
	{
		if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Party match is not on the EDT");
		PartyMatchCoordinator coordinator;
		PartyBattleWindow window;
		synchronized (stateLock)
		{
			if (lifecycleGeneration != generation || (requireRunning && !running)) return;
			coordinator = partyMatchCoordinator;
			window = partyBattleWindow;
			partyMatchCoordinator = null;
			partyBattleWindow = null;
			partyMatchId = null;
			pendingPartyDeck = null;
		}
		if (abort && coordinator != null) coordinator.abort();
		if (window != null) window.dispose();
	}

	private boolean isPartySessionCurrent(long generation, long sessionGeneration)
	{
		synchronized (stateLock)
		{
			return isCurrent(generation) && partySessionGeneration == sessionGeneration;
		}
	}

	private DeckProfileRepository loadProfileRepository()
	{
		DeckProfileRepository repository = new DeckProfileRepository(configManager, profileCodec);
		repository.reload();
		DeckProfile current = repository.getCurrent();
		List<Deck> decks = new ArrayList<>(current.getDecks());
		boolean changed = false;
		for (Deck starter : starterDeckFactory.createStarterDecks())
		{
			if (decks.stream().noneMatch(deck -> deck.getId().equals(starter.getId())))
			{
				decks.add(starter);
				changed = true;
			}
		}
		String selected = current.getSelectedDeckId().orElse(null);
		if (selected == null)
		{
			selected = StarterDeckFactory.DEATHRATTLE_ID;
			changed = true;
		}
		if (changed) repository.save(new DeckProfile(decks, selected));
		return repository;
	}

	private boolean isUnmodifiedStarter(Deck deck)
	{
		synchronized (stateLock)
		{
			return starterDeckFactory != null && starterDeckFactory.isUnmodifiedStarter(deck);
		}
	}

	private void refreshUi()
	{
		long generation;
		synchronized (stateLock)
		{
			if (!running)
			{
				return;
			}
			generation = lifecycleGeneration;
		}
		refreshUi(generation);
	}

	private void refreshUi(long generation)
	{
		OsrsTcgBattlesPanel currentPanel;
		DeckBuilderWindow currentBuilder;
		synchronized (stateLock)
		{
			if (!isCurrent(generation))
			{
				return;
			}
			currentPanel = panel;
			currentBuilder = deckBuilderWindow;
		}
		SwingUtilities.invokeLater(() -> {
			synchronized (stateLock)
			{
				if (!isCurrent(generation) || panel != currentPanel || deckBuilderWindow != currentBuilder)
				{
					return;
				}
				if (currentPanel != null)
				{
					currentPanel.refresh();
				}
				if (currentBuilder != null)
				{
					currentBuilder.refresh();
				}
			}
		});
	}

	private OsrsTcgBattlesPanel createPanelOnEdt(long generation)
	{
		OsrsTcgBattlesPanel[] result = new OsrsTcgBattlesPanel[1];
		runOnEdtAndWait(() -> {
			synchronized (stateLock)
			{
				if (isCurrent(generation))
				{
					result[0] = new OsrsTcgBattlesPanel(this);
				}
			}
		});
		return result[0];
	}

	private boolean isCurrent(long generation)
	{
		return running && lifecycleGeneration == generation;
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
			throw new IllegalStateException("Interrupted while updating plugin UI", ex);
		}
		catch (InvocationTargetException ex)
		{
			throw new IllegalStateException("Could not update plugin UI", ex.getCause());
		}
	}

	private final class EditorController implements BattleUiController
	{
		private final long generation;

		private EditorController(long generation)
		{
			this.generation = generation;
		}

		@Override
		public BattleCardCatalog getCatalog()
		{
			return OsrsTcgBattlesPlugin.this.getCatalog();
		}

		@Override
		public OwnedCardCollectionSnapshot getCollection()
		{
			return OsrsTcgBattlesPlugin.this.getCollection();
		}

		@Override
		public DeckProfile getDeckProfile()
		{
			return OsrsTcgBattlesPlugin.this.getDeckProfile();
		}

		@Override
		public DeckValidationResult validate(Deck deck)
		{
			return OsrsTcgBattlesPlugin.this.validate(deck);
		}

		@Override
		public DeckReadiness getDeckReadiness(Deck deck)
		{
			return OsrsTcgBattlesPlugin.this.getDeckReadiness(deck);
		}

		@Override
		public List<Deck> getStarterDecks()
		{
			return OsrsTcgBattlesPlugin.this.getStarterDecks();
		}

		@Override
		public boolean isStarterId(String deckId)
		{
			return OsrsTcgBattlesPlugin.this.isStarterId(deckId);
		}

		@Override
		public int copyLimit(BattleCard card)
		{
			return OsrsTcgBattlesPlugin.this.copyLimit(card);
		}

		@Override
		public void saveDeck(Deck deck, boolean select)
		{
			OsrsTcgBattlesPlugin.this.saveDeck(deck, select, generation);
		}

		@Override
		public void deleteDeck(String deckId)
		{
			OsrsTcgBattlesPlugin.this.deleteDeck(deckId, generation);
		}

		@Override
		public void selectDeck(String deckId)
		{
			OsrsTcgBattlesPlugin.this.selectDeck(deckId, generation);
		}

		@Override
		public void refreshCollection()
		{
			if (isGenerationCurrent())
			{
				OsrsTcgBattlesPlugin.this.refreshCollection();
			}
		}

		@Override
		public void openDeckBuilder()
		{
			if (isGenerationCurrent())
			{
				OsrsTcgBattlesPlugin.this.openDeckBuilder();
			}
		}

		@Override
		public void startDemoMatch()
		{
			if (isGenerationCurrent())
			{
				OsrsTcgBattlesPlugin.this.startDemoMatch();
			}
		}

		@Override
		public PartyDuelSnapshot getPartyDuelSnapshot()
		{
			return OsrsTcgBattlesPlugin.this.getPartyDuelSnapshot();
		}

		@Override
		public List<PartyOpponent> getPartyOpponents()
		{
			return OsrsTcgBattlesPlugin.this.getPartyOpponents();
		}

		@Override
		public String getPartyDuelMessage()
		{
			return OsrsTcgBattlesPlugin.this.getPartyDuelMessage();
		}

		@Override
		public void invitePartyOpponent(long memberId)
		{
			if (isGenerationCurrent()) OsrsTcgBattlesPlugin.this.invitePartyOpponent(memberId);
		}

		@Override
		public void acceptPartyDuel()
		{
			if (isGenerationCurrent()) OsrsTcgBattlesPlugin.this.acceptPartyDuel();
		}

		@Override
		public void declinePartyDuel()
		{
			if (isGenerationCurrent()) OsrsTcgBattlesPlugin.this.declinePartyDuel();
		}

		@Override
		public void abortPartyDuel()
		{
			if (isGenerationCurrent()) OsrsTcgBattlesPlugin.this.abortPartyDuel();
		}

		private boolean isGenerationCurrent()
		{
			synchronized (stateLock)
			{
				return isCurrent(generation);
			}
		}
	}

	private static final class PartyDeckMetadata
	{
		private final String catalogHash;
		private final int rulesetVersion;
		private final String deckId;
		private final String commitment;
		private final long sessionGeneration;

		private PartyDeckMetadata(String catalogHash, int rulesetVersion, String deckId, String commitment,
			long sessionGeneration)
		{
			this.catalogHash = catalogHash;
			this.rulesetVersion = rulesetVersion;
			this.deckId = deckId;
			this.commitment = commitment;
			this.sessionGeneration = sessionGeneration;
		}
	}

	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new Color(55, 38, 24));
			graphics.fillRoundRect(1, 1, 14, 14, 3, 3);
			graphics.setColor(new Color(218, 170, 70));
			graphics.drawRoundRect(2, 2, 11, 11, 2, 2);
			graphics.drawLine(5, 5, 11, 11);
			graphics.drawLine(11, 5, 5, 11);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}
}
