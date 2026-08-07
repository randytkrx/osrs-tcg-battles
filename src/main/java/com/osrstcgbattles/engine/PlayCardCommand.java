package com.osrstcgbattles.engine;

import java.util.Objects;

public final class PlayCardCommand implements Command
{
	private final PlayerId player;
	private final String cardId;
	private final String targetInstanceId;

	public PlayCardCommand(PlayerId player, String cardId)
	{
		this(player, cardId, (String) null);
	}

	public PlayCardCommand(PlayerId player, String cardId, String targetInstanceId)
	{
		this.player = Objects.requireNonNull(player, "player");
		this.cardId = Objects.requireNonNull(cardId, "cardId");
		this.targetInstanceId = targetInstanceId;
	}

	/** @deprecated Rows are ignored; use the row-free constructors. */
	@Deprecated
	public PlayCardCommand(PlayerId player, String cardId, Row row, String targetInstanceId)
	{
		this(player, cardId, targetInstanceId);
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

	/** @deprecated Rows no longer affect gameplay. */
	@Deprecated
	public Row getRow()
	{
		return null;
	}

	public java.util.Optional<String> getTargetInstanceId()
	{
		return java.util.Optional.ofNullable(targetInstanceId);
	}
}
