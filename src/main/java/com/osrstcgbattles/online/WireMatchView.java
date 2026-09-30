package com.osrstcgbattles.online;

import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.BoardState;
import com.osrstcgbattles.engine.BoardUnit;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.match.MatchView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON-safe match projection. Card definitions are represented by catalog IDs only. */
public final class WireMatchView
{
	private static final int MAX_COUNTER_VALUE = 1_000_000;
	private static final int MAX_GRAVEYARD_SIZE = DuelscapeEngine.DECK_SIZE * 2;

	private final String viewer;
	private final Integer turnNumber;
	private final String startingPlayer;
	private final String activePlayer;
	private final String status;
	private final String phase;
	private final String winner;
	private final String publicStateHash;
	private final Map<String, WirePlayerView> players;
	private final List<String> localHand;
	private final Map<String, List<WireBoardUnit>> board;

	private WireMatchView(MatchView view)
	{
		viewer = view.getViewer().name();
		turnNumber = view.getTurnNumber();
		startingPlayer = view.getStartingPlayer().name();
		activePlayer = view.getActivePlayer().map(Enum::name).orElse(null);
		status = view.getStatus().name();
		phase = view.getPhase().name();
		winner = view.getWinner().map(Enum::name).orElse(null);
		publicStateHash = view.getPublicStateHash();
		Map<String, WirePlayerView> projectedPlayers = new LinkedHashMap<>();
		Map<String, List<WireBoardUnit>> projectedBoard = new LinkedHashMap<>();
		for (PlayerId player : PlayerId.values())
		{
			projectedPlayers.put(player.name(), new WirePlayerView(view.getPlayer(player)));
			List<WireBoardUnit> units = new ArrayList<>();
			for (BoardUnit unit : view.getBoard().getUnits(player)) units.add(new WireBoardUnit(unit));
			projectedBoard.put(player.name(), Collections.unmodifiableList(units));
		}
		players = Collections.unmodifiableMap(projectedPlayers);
		board = Collections.unmodifiableMap(projectedBoard);
		List<String> hand = new ArrayList<>();
		for (Card card : view.getLocalHand()) hand.add(card.getId());
		localHand = Collections.unmodifiableList(hand);
	}

	public static WireMatchView from(MatchView view)
	{
		if (view == null) throw new IllegalArgumentException("view is required");
		return new WireMatchView(view);
	}

	public MatchView toMatchView(BattleCardCatalog catalog)
	{
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog);
		PlayerId viewingSeat = enumValue(PlayerId.class, viewer, "viewer");
		int validatedTurnNumber = bounded(turnNumber, 1, MAX_COUNTER_VALUE, "turnNumber");
		if (players == null) throw new IllegalArgumentException("missing players");
		if (board == null) throw new IllegalArgumentException("missing board");
		EnumMap<PlayerId, MatchView.PlayerView> projectedPlayers = new EnumMap<>(PlayerId.class);
		EnumMap<PlayerId, List<BoardUnit>> projectedBoard = new EnumMap<>(PlayerId.class);
		Set<String> instanceIds = new HashSet<>();
		for (PlayerId player : PlayerId.values())
		{
			WirePlayerView wirePlayer = players.get(player.name());
			if (wirePlayer == null) throw new IllegalArgumentException("missing player view");
			int heroHealth = bounded(wirePlayer.heroHealth, 0, DuelscapeEngine.HERO_HEALTH, "heroHealth");
			int maximumMana = bounded(wirePlayer.maximumMana, 0, DuelscapeEngine.MAXIMUM_MANA, "maximumMana");
			int mana = bounded(wirePlayer.mana, 0, DuelscapeEngine.MAXIMUM_MANA, "mana");
			int temporaryMana = bounded(wirePlayer.temporaryMana, 0, mana, "temporaryMana");
			if (mana - temporaryMana > maximumMana) throw new IllegalArgumentException("invalid mana");
			int fatigue = bounded(wirePlayer.fatigue, 0, MAX_COUNTER_VALUE, "fatigue");
			int turnsStarted = bounded(wirePlayer.turnsStarted, 0, MAX_COUNTER_VALUE, "turnsStarted");
			int handSize = bounded(wirePlayer.handSize, 0, DuelscapeEngine.HAND_LIMIT, "handSize");
			int drawPileSize = bounded(wirePlayer.drawPileSize, 0, DuelscapeEngine.DECK_SIZE, "drawPileSize");
			int remainingMulligans = bounded(wirePlayer.remainingMulligans, 0,
				DuelscapeEngine.OPENING_HAND_SIZE, "remainingMulligans");
			boolean mulliganFinished = required(wirePlayer.mulliganFinished, "mulliganFinished");
			List<Card> graveyardCards = new ArrayList<>();
			if (wirePlayer.graveyard == null) throw new IllegalArgumentException("missing graveyard");
			if (wirePlayer.graveyard.size() > MAX_GRAVEYARD_SIZE)
				throw new IllegalArgumentException("graveyard is too large");
			for (String cardId : wirePlayer.graveyard)
				graveyardCards.add(factory.createCard(requiredString(cardId, "graveyard card ID")));
			projectedPlayers.put(player, MatchView.PlayerView.visible(heroHealth, mana, temporaryMana, maximumMana,
				fatigue, turnsStarted, handSize, drawPileSize, graveyardCards, remainingMulligans,
				mulliganFinished));

			List<WireBoardUnit> wireUnits = board.get(player.name());
			if (wireUnits == null) throw new IllegalArgumentException("missing board");
			if (wireUnits.size() > DuelscapeEngine.BATTLEFIELD_LIMIT)
				throw new IllegalArgumentException("board is too large");
			List<BoardUnit> units = new ArrayList<>();
			for (WireBoardUnit unit : wireUnits)
			{
				if (unit == null) throw new IllegalArgumentException("null board unit");
				String instanceId = requiredString(unit.instanceId, "board instance ID");
				if (!instanceIds.add(instanceId)) throw new IllegalArgumentException("duplicate board instance ID");
				units.add(BoardUnit.visible(instanceId,
					factory.createBoardUnitCard(requiredString(unit.cardId, "board card ID")),
					bounded(unit.attack, 0, MAX_COUNTER_VALUE, "attack"),
					bounded(unit.health, 0, MAX_COUNTER_VALUE, "health"), required(unit.ready, "ready"),
					required(unit.shielded, "shielded"), required(unit.stealthed, "stealthed"),
					required(unit.rushRestricted, "rushRestricted")));
			}
			projectedBoard.put(player, units);
			if (handSize + drawPileSize + graveyardCards.size() + units.size() > DuelscapeEngine.DECK_SIZE)
				throw new IllegalArgumentException("player zones exceed deck size");
		}
		if (localHand == null) throw new IllegalArgumentException("missing local hand");
		if (localHand.size() > DuelscapeEngine.HAND_LIMIT) throw new IllegalArgumentException("local hand is too large");
		if (localHand.size() != projectedPlayers.get(viewingSeat).getHandSize())
			throw new IllegalArgumentException("local hand size does not match viewer handSize");
		List<Card> hand = new ArrayList<>();
		for (String cardId : localHand) hand.add(factory.createCard(requiredString(cardId, "local hand card ID")));
		if (publicStateHash == null || !publicStateHash.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("invalid public state hash");
		PlayerId validatedActivePlayer = optionalEnum(PlayerId.class, activePlayer);
		MatchStatus validatedStatus = enumValue(MatchStatus.class, status, "status");
		MatchPhase validatedPhase = enumValue(MatchPhase.class, phase, "phase");
		PlayerId validatedWinner = optionalEnum(PlayerId.class, winner);
		if (validatedStatus == MatchStatus.ACTIVE
			&& (validatedPhase == MatchPhase.COMPLETE || validatedActivePlayer == null || validatedWinner != null))
			throw new IllegalArgumentException("active match fields are inconsistent");
		if (validatedStatus == MatchStatus.COMPLETE
			&& (validatedPhase != MatchPhase.COMPLETE || validatedActivePlayer != null))
			throw new IllegalArgumentException("completed match fields are inconsistent");
		return MatchView.visible(viewingSeat, validatedTurnNumber,
			enumValue(PlayerId.class, startingPlayer, "startingPlayer"), validatedActivePlayer, validatedStatus,
			validatedPhase, validatedWinner, projectedPlayers,
			BoardState.visible(projectedBoard), hand, publicStateHash);
	}

	private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String name)
	{
		if (value == null) throw new IllegalArgumentException("missing " + name);
		try { return Enum.valueOf(type, value); }
		catch (IllegalArgumentException exception) { throw new IllegalArgumentException("invalid " + name, exception); }
	}

	private static <T extends Enum<T>> T optionalEnum(Class<T> type, String value)
	{
		return value == null ? null : enumValue(type, value, type.getSimpleName());
	}

	private static int bounded(Integer value, int minimum, int maximum, String name)
	{
		if (value == null) throw new IllegalArgumentException("missing " + name);
		if (value < minimum || value > maximum) throw new IllegalArgumentException("invalid " + name);
		return value;
	}

	private static boolean required(Boolean value, String name)
	{
		if (value == null) throw new IllegalArgumentException("missing " + name);
		return value;
	}

	private static String requiredString(String value, String name)
	{
		if (value == null || value.isEmpty() || value.length() > 128
			|| value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("missing " + name);
		return value;
	}

	public String getViewer() { return viewer; }
	public int getTurnNumber() { return turnNumber; }
	public String getStartingPlayer() { return startingPlayer; }
	public String getActivePlayer() { return activePlayer; }
	public String getStatus() { return status; }
	public String getPhase() { return phase; }
	public String getWinner() { return winner; }
	public String getPublicStateHash() { return publicStateHash; }
	public Map<String, WirePlayerView> getPlayers() { return players; }
	public List<String> getLocalHand() { return localHand; }
	public Map<String, List<WireBoardUnit>> getBoard() { return board; }

	public static final class WirePlayerView
	{
		private final Integer heroHealth;
		private final Integer mana;
		private final Integer temporaryMana;
		private final Integer maximumMana;
		private final Integer fatigue;
		private final Integer turnsStarted;
		private final Integer handSize;
		private final Integer drawPileSize;
		private final List<String> graveyard;
		private final Integer remainingMulligans;
		private final Boolean mulliganFinished;

		private WirePlayerView(MatchView.PlayerView player)
		{
			heroHealth = player.getHeroHealth();
			mana = player.getMana();
			temporaryMana = player.getTemporaryMana();
			maximumMana = player.getMaximumMana();
			fatigue = player.getFatigue();
			turnsStarted = player.getTurnsStarted();
			handSize = player.getHandSize();
			drawPileSize = player.getDrawPileSize();
			List<String> cards = new ArrayList<>();
			for (Card card : player.getGraveyard()) cards.add(card.getId());
			graveyard = Collections.unmodifiableList(cards);
			remainingMulligans = player.getRemainingMulligans();
			mulliganFinished = player.isMulliganFinished();
		}

		public int getHeroHealth() { return heroHealth; }
		public int getMana() { return mana; }
		public int getTemporaryMana() { return temporaryMana; }
		public int getMaximumMana() { return maximumMana; }
		public int getFatigue() { return fatigue; }
		public int getTurnsStarted() { return turnsStarted; }
		public int getHandSize() { return handSize; }
		public int getDrawPileSize() { return drawPileSize; }
		public List<String> getGraveyard() { return graveyard; }
		public int getRemainingMulligans() { return remainingMulligans; }
		public boolean isMulliganFinished() { return mulliganFinished; }
	}

	public static final class WireBoardUnit
	{
		private final String instanceId;
		private final String cardId;
		private final Integer attack;
		private final Integer health;
		private final Boolean ready;
		private final Boolean shielded;
		private final Boolean stealthed;
		private final Boolean rushRestricted;

		private WireBoardUnit(BoardUnit unit)
		{
			instanceId = unit.getInstanceId();
			cardId = unit.getDefinition().getId();
			attack = unit.getCurrentAttack();
			health = unit.getCurrentHealth();
			ready = unit.isReady();
			shielded = unit.isShielded();
			stealthed = unit.isStealthed();
			rushRestricted = unit.isRushRestricted();
		}

		public String getInstanceId() { return instanceId; }
		public String getCardId() { return cardId; }
		public int getAttack() { return attack; }
		public int getHealth() { return health; }
		public boolean isReady() { return ready; }
		public boolean isShielded() { return shielded; }
		public boolean isStealthed() { return stealthed; }
		public boolean isRushRestricted() { return rushRestricted; }
	}
}
