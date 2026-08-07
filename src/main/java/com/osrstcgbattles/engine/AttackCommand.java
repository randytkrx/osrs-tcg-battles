package com.osrstcgbattles.engine;

import java.util.Objects;
import java.util.Optional;

public final class AttackCommand implements Command
{
	private final PlayerId player;
	private final String attackerInstanceId;
	private final String targetInstanceId;

	/** Attacks the opposing hero. */
	public AttackCommand(PlayerId player, String attackerInstanceId)
	{
		this(player, attackerInstanceId, null);
	}

	/** Attacks an opposing unit. */
	public AttackCommand(PlayerId player, String attackerInstanceId, String targetInstanceId)
	{
		this.player = Objects.requireNonNull(player, "player");
		this.attackerInstanceId = Objects.requireNonNull(attackerInstanceId, "attackerInstanceId");
		this.targetInstanceId = targetInstanceId;
	}

	@Override
	public PlayerId getPlayer() { return player; }
	public String getAttackerInstanceId() { return attackerInstanceId; }
	public Optional<String> getTargetInstanceId() { return Optional.ofNullable(targetInstanceId); }
}
