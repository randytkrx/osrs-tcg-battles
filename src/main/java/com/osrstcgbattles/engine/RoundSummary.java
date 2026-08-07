package com.osrstcgbattles.engine;

import java.util.Objects;

public final class RoundSummary
{
	private final int roundNumber;
	private final int playerOneScore;
	private final int playerTwoScore;
	private final RoundOutcome outcome;

	RoundSummary(int roundNumber, int playerOneScore, int playerTwoScore, RoundOutcome outcome)
	{
		this.roundNumber = roundNumber;
		this.playerOneScore = playerOneScore;
		this.playerTwoScore = playerTwoScore;
		this.outcome = Objects.requireNonNull(outcome, "outcome");
	}

	public int getRoundNumber()
	{
		return roundNumber;
	}

	public int getPlayerOneScore()
	{
		return playerOneScore;
	}

	public int getPlayerTwoScore()
	{
		return playerTwoScore;
	}

	public RoundOutcome getOutcome()
	{
		return outcome;
	}
}
