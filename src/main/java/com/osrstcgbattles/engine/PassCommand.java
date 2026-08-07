package com.osrstcgbattles.engine;

import java.util.Objects;

public final class PassCommand implements Command
{
	private final PlayerId player;

	public PassCommand(PlayerId player)
	{
		this.player = Objects.requireNonNull(player, "player");
	}

	@Override
	public PlayerId getPlayer()
	{
		return player;
	}
}
