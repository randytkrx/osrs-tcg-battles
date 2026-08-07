package com.osrstcgbattles.engine;

import java.util.Objects;

public final class ConcedeCommand implements Command
{
	private final PlayerId player;

	public ConcedeCommand(PlayerId player)
	{
		this.player = Objects.requireNonNull(player, "player");
	}

	@Override
	public PlayerId getPlayer()
	{
		return player;
	}
}
