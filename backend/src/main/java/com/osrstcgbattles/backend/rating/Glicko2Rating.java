package com.osrstcgbattles.backend.rating;

/**
 * An immutable Glicko-2 rating and its lifetime game record.
 *
 * <p>Rating and rating deviation use the conventional Glicko display scale.
 * Volatility is stored on the internal Glicko-2 scale.</p>
 */
public record Glicko2Rating(
	double rating,
	double ratingDeviation,
	double volatility,
	long gamesPlayed,
	long wins,
	long losses,
	long draws)
{
	public static final double DEFAULT_RATING = 1500.0;
	public static final double DEFAULT_RATING_DEVIATION = 350.0;
	public static final double DEFAULT_VOLATILITY = 0.06;

	public Glicko2Rating
	{
		if (!Double.isFinite(rating))
		{
			throw new IllegalArgumentException("rating must be finite");
		}
		if (!Double.isFinite(ratingDeviation) || ratingDeviation <= 0.0)
		{
			throw new IllegalArgumentException("rating deviation must be finite and positive");
		}
		if (!Double.isFinite(volatility) || volatility <= 0.0)
		{
			throw new IllegalArgumentException("volatility must be finite and positive");
		}
		if (gamesPlayed < 0 || wins < 0 || losses < 0 || draws < 0)
		{
			throw new IllegalArgumentException("game counts cannot be negative");
		}
		long recordedGames;
		try
		{
			recordedGames = Math.addExact(Math.addExact(wins, losses), draws);
		}
		catch (ArithmeticException exception)
		{
			throw new IllegalArgumentException("game counts overflow", exception);
		}
		if (gamesPlayed != recordedGames)
		{
			throw new IllegalArgumentException("games played must equal wins + losses + draws");
		}
	}

	public Glicko2Rating(double rating, double ratingDeviation, double volatility)
	{
		this(rating, ratingDeviation, volatility, 0, 0, 0, 0);
	}

	public static Glicko2Rating unrated()
	{
		return new Glicko2Rating(DEFAULT_RATING, DEFAULT_RATING_DEVIATION, DEFAULT_VOLATILITY);
	}
}
