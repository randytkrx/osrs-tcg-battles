package com.osrstcgbattles.engine;

public enum PlayerId
{
	PLAYER_ONE,
	PLAYER_TWO;

	public PlayerId opponent()
	{
		return this == PLAYER_ONE ? PLAYER_TWO : PLAYER_ONE;
	}
}
