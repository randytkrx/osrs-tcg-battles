package com.osrstcgbattles.backend.rating;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Calculates Glicko-2 updates as specified by Mark Glickman's canonical algorithm. */
public final class Glicko2Calculator
{
	public static final double DEFAULT_TAU = 0.5;

	private static final double SCALE = 173.7178;
	private static final double CONVERGENCE_TOLERANCE = 0.000001;
	private static final int MAX_VOLATILITY_ITERATIONS = 1_000;
	private static final int MAX_BRACKET_STEPS = 1_000;

	private final double tau;

	public Glicko2Calculator()
	{
		this(DEFAULT_TAU);
	}

	/**
	 * @param tau volatility constraint, commonly between 0.3 and 1.2
	 */
	public Glicko2Calculator(double tau)
	{
		if (!Double.isFinite(tau) || tau <= 0.0)
		{
			throw new IllegalArgumentException("tau must be finite and positive");
		}
		this.tau = tau;
	}

	public double getTau()
	{
		return tau;
	}

	/**
	 * Updates a player once for the supplied rating period. Opponent ratings are
	 * always read from before the period; callers must not feed intermediate updates back in.
	 */
	public Glicko2Rating calculate(Glicko2Rating player, List<RatingPeriodGame> games)
	{
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(games, "games");

		double mu = toInternalRating(player.rating());
		double phi = toInternalDeviation(player.ratingDeviation());
		double sigma = player.volatility();
		if (games.isEmpty())
		{
			double newDeviation = toDisplayDeviation(Math.hypot(phi, sigma));
			return new Glicko2Rating(player.rating(), newDeviation, sigma,
				player.gamesPlayed(), player.wins(), player.losses(), player.draws());
		}

		double varianceInverse = 0.0;
		double improvementSum = 0.0;
		long wins = player.wins();
		long losses = player.losses();
		long draws = player.draws();

		for (RatingPeriodGame game : games)
		{
			Objects.requireNonNull(game, "games cannot contain null");
			double opponentMu = toInternalRating(game.opponentRating());
			double opponentPhi = toInternalDeviation(game.opponentRatingDeviation());
			double impact = impact(opponentPhi);
			double expected = expectedScore(mu, opponentMu, impact);
			varianceInverse += impact * impact * expected * (1.0 - expected);
			improvementSum += impact * (game.outcome().getScore() - expected);
			switch (game.outcome())
			{
				case WIN -> wins = increment(wins);
				case LOSS -> losses = increment(losses);
				case DRAW -> draws = increment(draws);
			}
		}

		if (!(varianceInverse > 0.0) || !Double.isFinite(varianceInverse))
		{
			throw new ArithmeticException("rating period has no finite statistical information");
		}
		double variance = 1.0 / varianceInverse;
		double delta = variance * improvementSum;
		double newSigma = calculateVolatility(phi, sigma, variance, delta);
		double preRatingDeviation = Math.hypot(phi, newSigma);
		double newPhi = 1.0 / Math.sqrt(1.0 / (preRatingDeviation * preRatingDeviation) + 1.0 / variance);
		double newMu = mu + newPhi * newPhi * improvementSum;
		long gamesPlayed;
		try
		{
			gamesPlayed = Math.addExact(player.gamesPlayed(), games.size());
		}
		catch (ArithmeticException exception)
		{
			throw new IllegalArgumentException("games played overflow", exception);
		}

		return new Glicko2Rating(toDisplayRating(newMu), toDisplayDeviation(newPhi), newSigma,
			gamesPlayed, wins, losses, draws);
	}

	public Glicko2Rating calculate(Glicko2Rating player, RatingPeriodGame... games)
	{
		Objects.requireNonNull(games, "games");
		return calculate(player, Arrays.asList(games));
	}

	private double calculateVolatility(double phi, double sigma, double variance, double delta)
	{
		double alpha = Math.log(sigma * sigma);
		double a = alpha;
		double deltaSquared = delta * delta;
		double b;
		if (deltaSquared > phi * phi + variance)
		{
			b = Math.log(deltaSquared - phi * phi - variance);
		}
		else
		{
			int k = 1;
			b = alpha - k * tau;
			while (volatilityFunction(b, deltaSquared, phi, variance, alpha) < 0.0)
			{
				if (++k > MAX_BRACKET_STEPS)
				{
					throw new ArithmeticException("could not bracket volatility solution");
				}
				b = alpha - k * tau;
			}
		}

		double fA = volatilityFunction(a, deltaSquared, phi, variance, alpha);
		double fB = volatilityFunction(b, deltaSquared, phi, variance, alpha);
		int iterations = 0;
		while (Math.abs(b - a) > CONVERGENCE_TOLERANCE)
		{
			if (++iterations > MAX_VOLATILITY_ITERATIONS)
			{
				throw new ArithmeticException("volatility calculation did not converge");
			}
			double denominator = fB - fA;
			if (denominator == 0.0 || !Double.isFinite(denominator))
			{
				throw new ArithmeticException("volatility calculation became unstable");
			}
			double c = a + (a - b) * fA / denominator;
			double fC = volatilityFunction(c, deltaSquared, phi, variance, alpha);
			if (fC * fB <= 0.0)
			{
				a = b;
				fA = fB;
			}
			else
			{
				fA /= 2.0;
			}
			b = c;
			fB = fC;
		}
		return Math.exp(a / 2.0);
	}

	private double volatilityFunction(double x, double deltaSquared, double phi, double variance, double alpha)
	{
		double exponential = Math.exp(x);
		double denominator = phi * phi + variance + exponential;
		return exponential * (deltaSquared - denominator) / (2.0 * denominator * denominator)
			- (x - alpha) / (tau * tau);
	}

	private static double impact(double deviation)
	{
		return 1.0 / Math.sqrt(1.0 + 3.0 * deviation * deviation / (Math.PI * Math.PI));
	}

	private static double expectedScore(double rating, double opponentRating, double impact)
	{
		double exponent = -impact * (rating - opponentRating);
		if (exponent >= 0.0)
		{
			double exponential = Math.exp(-exponent);
			return exponential / (1.0 + exponential);
		}
		double exponential = Math.exp(exponent);
		return 1.0 / (1.0 + exponential);
	}

	private static long increment(long value)
	{
		try
		{
			return Math.incrementExact(value);
		}
		catch (ArithmeticException exception)
		{
			throw new IllegalArgumentException("game count overflow", exception);
		}
	}

	private static double toInternalRating(double rating)
	{
		return (rating - Glicko2Rating.DEFAULT_RATING) / SCALE;
	}

	private static double toInternalDeviation(double deviation)
	{
		return deviation / SCALE;
	}

	private static double toDisplayRating(double rating)
	{
		return rating * SCALE + Glicko2Rating.DEFAULT_RATING;
	}

	private static double toDisplayDeviation(double deviation)
	{
		return deviation * SCALE;
	}
}
