package com.osrstcgbattles.engine;

import java.util.Objects;

public final class EndTurnCommand implements Command
{
	private final PlayerId player;

	public EndTurnCommand(PlayerId player)
	{
		this.player = Objects.requireNonNull(player, "player");
	}

	@Override
	public PlayerId getPlayer()
	{
		return player;
	}
}
