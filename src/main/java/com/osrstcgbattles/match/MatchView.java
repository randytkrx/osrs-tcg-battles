package com.osrstcgbattles.match;

import com.osrstcgbattles.engine.BoardState;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.PlayerState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable player-scoped projection of a match that excludes hidden opponent cards. */
public final class MatchView
{
	private final PlayerId viewer;
	private final int turnNumber;
	private final PlayerId startingPlayer;
	private final PlayerId activePlayer;
	private final MatchStatus status;
	private final MatchPhase phase;
	private final PlayerId winner;
	private final Map<PlayerId, PlayerView> players;
	private final BoardState board;
	private final List<Card> localHand;
	private final String publicStateHash;

	private MatchView(PlayerId viewer, MatchState state)
	{
		this.viewer = Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(state, "state");
		turnNumber = state.getTurnNumber();
		startingPlayer = state.getStartingPlayer();
		activePlayer = state.getActivePlayer().orElse(null);
		status = state.getStatus();
		phase = state.getPhase();
		winner = state.getWinner().orElse(null);
		EnumMap<PlayerId, PlayerView> projectedPlayers = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values())
		{
			projectedPlayers.put(player, new PlayerView(state.getPlayer(player)));
		}
		players = Collections.unmodifiableMap(projectedPlayers);
		board = state.getBoard();
		localHand = Collections.unmodifiableList(new ArrayList<>(state.getPlayer(viewer).getHand()));
		publicStateHash = state.getPublicStateHash();
	}

	private MatchView(PlayerId viewer, int turnNumber, PlayerId startingPlayer, PlayerId activePlayer,
		MatchStatus status, MatchPhase phase, PlayerId winner, Map<PlayerId, PlayerView> players,
		BoardState board, List<Card> localHand, String publicStateHash)
	{
		this.viewer = Objects.requireNonNull(viewer, "viewer");
		if (turnNumber < 1) throw new IllegalArgumentException("turnNumber must be positive");
		this.turnNumber = turnNumber;
		this.startingPlayer = Objects.requireNonNull(startingPlayer, "startingPlayer");
		this.activePlayer = activePlayer;
		this.status = Objects.requireNonNull(status, "status");
		this.phase = Objects.requireNonNull(phase, "phase");
		this.winner = winner;
		EnumMap<PlayerId, PlayerView> projectedPlayers = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values())
		{
			PlayerView projected = Objects.requireNonNull(players.get(player), "missing player view");
			projectedPlayers.put(player, projected);
		}
		this.players = Collections.unmodifiableMap(projectedPlayers);
		this.board = Objects.requireNonNull(board, "board");
		this.localHand = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(localHand, "localHand")));
		if (this.localHand.contains(null)) throw new IllegalArgumentException("localHand contains null");
		this.publicStateHash = Objects.requireNonNull(publicStateHash, "publicStateHash");
	}

	public static MatchView forPlayer(MatchState state, PlayerId viewer)
	{
		return new MatchView(viewer, state);
	}

	public static MatchView visible(PlayerId viewer, int turnNumber, PlayerId startingPlayer, PlayerId activePlayer,
		MatchStatus status, MatchPhase phase, PlayerId winner, Map<PlayerId, PlayerView> players,
		BoardState board, List<Card> localHand, String publicStateHash)
	{
		return new MatchView(viewer, turnNumber, startingPlayer, activePlayer, status, phase, winner,
			Objects.requireNonNull(players, "players"), board, localHand, publicStateHash);
	}

	public PlayerId getViewer() { return viewer; }
	public int getTurnNumber() { return turnNumber; }
	public PlayerId getStartingPlayer() { return startingPlayer; }
	public Optional<PlayerId> getActivePlayer() { return Optional.ofNullable(activePlayer); }
	public MatchStatus getStatus() { return status; }
	public MatchPhase getPhase() { return phase; }
	public Optional<PlayerId> getWinner() { return Optional.ofNullable(winner); }
	public PlayerView getPlayer(PlayerId player) { return players.get(Objects.requireNonNull(player, "player")); }
	public BoardState getBoard() { return board; }
	public List<Card> getLocalHand() { return localHand; }
	public String getPublicStateHash() { return publicStateHash; }

	/** Public player data. Hands and draw piles are represented only by counts. */
	public static final class PlayerView
	{
		private final int heroHealth;
		private final int mana;
		private final int temporaryMana;
		private final int maximumMana;
		private final int fatigue;
		private final int turnsStarted;
		private final int handSize;
		private final int drawPileSize;
		private final List<Card> graveyard;
		private final int remainingMulligans;
		private final boolean mulliganFinished;

		private PlayerView(PlayerState state)
		{
			heroHealth = state.getHeroHealth();
			mana = state.getMana();
			temporaryMana = state.getTemporaryMana();
			maximumMana = state.getMaximumMana();
			fatigue = state.getFatigue();
			turnsStarted = state.getTurnsStarted();
			handSize = state.getHand().size();
			drawPileSize = state.getDrawPile().size();
			graveyard = Collections.unmodifiableList(new ArrayList<>(state.getGraveyard()));
			remainingMulligans = state.getRemainingMulligans();
			mulliganFinished = state.isMulliganFinished();
		}

		private PlayerView(int heroHealth, int mana, int temporaryMana, int maximumMana, int fatigue,
			int turnsStarted, int handSize, int drawPileSize, List<Card> graveyard, int remainingMulligans,
			boolean mulliganFinished)
		{
			if (heroHealth < 0 || mana < 0 || temporaryMana < 0 || maximumMana < 0 || fatigue < 0
				|| turnsStarted < 0 || handSize < 0 || drawPileSize < 0 || remainingMulligans < 0)
				throw new IllegalArgumentException("visible player values must not be negative");
			this.heroHealth = heroHealth;
			this.mana = mana;
			this.temporaryMana = temporaryMana;
			this.maximumMana = maximumMana;
			this.fatigue = fatigue;
			this.turnsStarted = turnsStarted;
			this.handSize = handSize;
			this.drawPileSize = drawPileSize;
			this.graveyard = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(graveyard, "graveyard")));
			if (this.graveyard.contains(null)) throw new IllegalArgumentException("graveyard contains null");
			this.remainingMulligans = remainingMulligans;
			this.mulliganFinished = mulliganFinished;
		}

		public static PlayerView visible(int heroHealth, int mana, int temporaryMana, int maximumMana,
			int fatigue, int turnsStarted, int handSize, int drawPileSize, List<Card> graveyard,
			int remainingMulligans, boolean mulliganFinished)
		{
			return new PlayerView(heroHealth, mana, temporaryMana, maximumMana, fatigue, turnsStarted,
				handSize, drawPileSize, graveyard, remainingMulligans, mulliganFinished);
		}

		public int getHeroHealth() { return heroHealth; }
		public int getMana() { return mana; }
		public int getTemporaryMana() { return temporaryMana; }
		public int getMaximumMana() { return maximumMana; }
		public int getFatigue() { return fatigue; }
		public int getTurnsStarted() { return turnsStarted; }
		public int getHandSize() { return handSize; }
		public int getDrawPileSize() { return drawPileSize; }
		public List<Card> getGraveyard() { return graveyard; }
		public int getRemainingMulligans() { return remainingMulligans; }
		public boolean isMulliganFinished() { return mulliganFinished; }
	}
}
