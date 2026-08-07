package com.osrstcgbattles.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MatchState
{
	private final int turnNumber;
	private final PlayerId startingPlayer;
	private final PlayerId activePlayer;
	private final MatchStatus status;
	private final MatchPhase phase;
	private final PlayerId winner;
	private final Map<PlayerId, PlayerState> players;
	private final BoardState board;
	private final long nextUnitInstanceId;

	MatchState(int turnNumber, PlayerId startingPlayer, PlayerId activePlayer, MatchStatus status,
		MatchPhase phase, PlayerId winner, Map<PlayerId, PlayerState> players, BoardState board,
		long nextUnitInstanceId)
	{
		this.turnNumber = turnNumber;
		this.startingPlayer = Objects.requireNonNull(startingPlayer, "startingPlayer");
		this.activePlayer = activePlayer;
		this.status = Objects.requireNonNull(status, "status");
		this.phase = Objects.requireNonNull(phase, "phase");
		this.winner = winner;
		EnumMap<PlayerId, PlayerState> copy = new EnumMap<>(PlayerId.class);
		copy.putAll(players);
		this.players = Collections.unmodifiableMap(copy);
		this.board = Objects.requireNonNull(board, "board");
		this.nextUnitInstanceId = nextUnitInstanceId;
	}

	public int getTurnNumber() { return turnNumber; }
	public PlayerId getStartingPlayer() { return startingPlayer; }
	public Optional<PlayerId> getActivePlayer() { return Optional.ofNullable(activePlayer); }
	public MatchStatus getStatus() { return status; }
	public MatchPhase getPhase() { return phase; }
	public Optional<PlayerId> getWinner() { return Optional.ofNullable(winner); }
	public PlayerState getPlayer(PlayerId player) { return players.get(Objects.requireNonNull(player, "player")); }
	public BoardState getBoard() { return board; }
	public String getPublicStateHash() { return StateHasher.publicStateSha256(this); }
	public String getSynchronizationStateHash() { return StateHasher.synchronizationStateSha256(this); }
	long getNextUnitInstanceId() { return nextUnitInstanceId; }

	/** @deprecated Use {@link #getTurnNumber()}. */
	@Deprecated
	public int getRoundNumber() { return turnNumber; }
	/** @deprecated Use {@link #getStartingPlayer()}. */
	@Deprecated
	public PlayerId getRoundStarter() { return startingPlayer; }
	/** @deprecated Rounds were removed. */
	@Deprecated
	public List<RoundSummary> getRoundSummaries() { return Collections.emptyList(); }
}
