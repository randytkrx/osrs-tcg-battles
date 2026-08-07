package com.osrstcgbattles.engine;

import java.util.Objects;

public final class MulliganCommand implements Command
{
	private final PlayerId player;
	private final String cardId;

	public MulliganCommand(PlayerId player, String cardId)
	{
		this.player = Objects.requireNonNull(player, "player");
		this.cardId = Objects.requireNonNull(cardId, "cardId");
	}

	@Override
	public PlayerId getPlayer()
	{
		return player;
	}

	public String getCardId()
	{
		return cardId;
	}
}
