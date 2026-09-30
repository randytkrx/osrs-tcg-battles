package com.osrstcgbattles.backend.rating;

/** The result of a game from the rated player's perspective. */
public enum Outcome
{
	WIN(1.0),
	DRAW(0.5),
	LOSS(0.0);

	private final double score;

	Outcome(double score)
	{
		this.score = score;
	}

	public double getScore()
	{
		return score;
	}
}
