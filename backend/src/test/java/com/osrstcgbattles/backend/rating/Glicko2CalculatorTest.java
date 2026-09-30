package com.osrstcgbattles.backend.rating;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class Glicko2CalculatorTest
{
	private static final double TOLERANCE = 0.01;

	@Test
	public void matchesCanonicalGlickmanExample()
	{
		Glicko2Rating player = new Glicko2Rating(1500.0, 200.0, 0.06);
		List<RatingPeriodGame> games = List.of(
			new RatingPeriodGame(1400.0, 30.0, Outcome.WIN),
			new RatingPeriodGame(1550.0, 100.0, Outcome.LOSS),
			new RatingPeriodGame(1700.0, 300.0, Outcome.LOSS));

		Glicko2Rating result = new Glicko2Calculator(0.5).calculate(player, games);

		assertEquals(1464.06, result.rating(), TOLERANCE);
		assertEquals(151.52, result.ratingDeviation(), TOLERANCE);
		assertEquals(0.059996, result.volatility(), 0.000001);
		assertEquals(3, result.gamesPlayed());
		assertEquals(1, result.wins());
		assertEquals(2, result.losses());
		assertEquals(0, result.draws());
	}

	@Test
	public void updatesAgainstOneOpponentAndRecordsDraw()
	{
		Glicko2Rating player = Glicko2Rating.unrated();
		Glicko2Rating result = new Glicko2Calculator().calculate(player,
			new RatingPeriodGame(1500.0, 350.0, Outcome.DRAW));

		assertEquals(1500.0, result.rating(), 0.0000001);
		assertTrue(result.ratingDeviation() < player.ratingDeviation());
		assertEquals(1, result.gamesPlayed());
		assertEquals(1, result.draws());
	}

	@Test
	public void appliesAllGamesAsOneRatingPeriod()
	{
		Glicko2Calculator calculator = new Glicko2Calculator();
		Glicko2Rating player = new Glicko2Rating(1500.0, 200.0, 0.06);
		RatingPeriodGame win = new RatingPeriodGame(1400.0, 30.0, Outcome.WIN);
		RatingPeriodGame loss = new RatingPeriodGame(1550.0, 100.0, Outcome.LOSS);

		Glicko2Rating batch = calculator.calculate(player, win, loss);
		Glicko2Rating sequential = calculator.calculate(calculator.calculate(player, win), loss);

		assertTrue(Math.abs(batch.rating() - sequential.rating()) > 0.1);
		assertEquals(2, batch.gamesPlayed());
		assertEquals(1, batch.wins());
		assertEquals(1, batch.losses());
	}

	@Test
	public void inactivePeriodOnlyIncreasesDeviation()
	{
		Glicko2Rating player = new Glicko2Rating(1620.0, 80.0, 0.07, 8, 5, 2, 1);
		Glicko2Rating result = new Glicko2Calculator().calculate(player, List.of());

		assertEquals(player.rating(), result.rating(), 0.0);
		assertEquals(player.volatility(), result.volatility(), 0.0);
		assertTrue(result.ratingDeviation() > player.ratingDeviation());
		assertEquals(player.gamesPlayed(), result.gamesPlayed());
		assertEquals(player.wins(), result.wins());
		assertEquals(player.losses(), result.losses());
		assertEquals(player.draws(), result.draws());
	}

	@Test
	public void accumulatesExistingRecord()
	{
		Glicko2Rating player = new Glicko2Rating(1500.0, 100.0, 0.06, 10, 6, 3, 1);
		Glicko2Rating result = new Glicko2Calculator().calculate(player,
			new RatingPeriodGame(1500.0, 100.0, Outcome.WIN),
			new RatingPeriodGame(1500.0, 100.0, Outcome.DRAW),
			new RatingPeriodGame(1500.0, 100.0, Outcome.LOSS));

		assertEquals(13, result.gamesPlayed());
		assertEquals(7, result.wins());
		assertEquals(4, result.losses());
		assertEquals(2, result.draws());
	}

	@Test
	public void createsConventionalUnratedPlayer()
	{
		Glicko2Rating rating = Glicko2Rating.unrated();

		assertEquals(1500.0, rating.rating(), 0.0);
		assertEquals(350.0, rating.ratingDeviation(), 0.0);
		assertEquals(0.06, rating.volatility(), 0.0);
		assertEquals(0, rating.gamesPlayed());
	}

	@Test
	public void validatesCalculatorInputs()
	{
		assertThrows(IllegalArgumentException.class, () -> new Glicko2Calculator(0.0));
		assertThrows(IllegalArgumentException.class, () -> new Glicko2Calculator(Double.NaN));
		Glicko2Calculator calculator = new Glicko2Calculator();
		assertThrows(NullPointerException.class, () -> calculator.calculate(null, List.of()));
		assertThrows(NullPointerException.class, () -> calculator.calculate(Glicko2Rating.unrated(),
			(List<RatingPeriodGame>) null));
		assertThrows(NullPointerException.class, () -> calculator.calculate(Glicko2Rating.unrated(),
			new RatingPeriodGame[] {null}));
	}

	@Test
	public void validatesRatingState()
	{
		assertThrows(IllegalArgumentException.class, () -> new Glicko2Rating(Double.NaN, 100.0, 0.06));
		assertThrows(IllegalArgumentException.class, () -> new Glicko2Rating(1500.0, 0.0, 0.06));
		assertThrows(IllegalArgumentException.class, () -> new Glicko2Rating(1500.0, 100.0, -0.1));
		assertThrows(IllegalArgumentException.class,
			() -> new Glicko2Rating(1500.0, 100.0, 0.06, 2, 1, 0, 0));
		assertThrows(IllegalArgumentException.class,
			() -> new Glicko2Rating(1500.0, 100.0, 0.06, -1, 0, 0, 0));
	}

	@Test
	public void validatesGameInputs()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new RatingPeriodGame(Double.POSITIVE_INFINITY, 100.0, Outcome.WIN));
		assertThrows(IllegalArgumentException.class,
			() -> new RatingPeriodGame(1500.0, -1.0, Outcome.WIN));
		assertThrows(NullPointerException.class,
			() -> new RatingPeriodGame(1500.0, 100.0, null));
		assertThrows(NullPointerException.class,
			() -> new RatingPeriodGame((Glicko2Rating) null, Outcome.WIN));
	}
}
