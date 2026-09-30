package com.osrstcgbattles.backend.rating;

import java.util.Objects;

/** An opponent and result included in one Glicko-2 rating period. */
public record RatingPeriodGame(double opponentRating, double opponentRatingDeviation, Outcome outcome)
{
	public RatingPeriodGame
	{
		if (!Double.isFinite(opponentRating))
		{
			throw new IllegalArgumentException("opponent rating must be finite");
		}
		if (!Double.isFinite(opponentRatingDeviation) || opponentRatingDeviation <= 0.0)
		{
			throw new IllegalArgumentException("opponent rating deviation must be finite and positive");
		}
		Objects.requireNonNull(outcome, "outcome");
	}

	public RatingPeriodGame(Glicko2Rating opponent, Outcome outcome)
	{
		this(Objects.requireNonNull(opponent, "opponent").rating(), opponent.ratingDeviation(), outcome);
	}
}
