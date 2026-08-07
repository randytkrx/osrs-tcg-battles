package com.osrstcgbattles.engine;

import java.util.Objects;

public final class FinishMulliganCommand implements Command
{
	private final PlayerId player;

	public FinishMulliganCommand(PlayerId player)
	{
		this.player = Objects.requireNonNull(player, "player");
	}

	@Override
	public PlayerId getPlayer()
	{
		return player;
	}
}
