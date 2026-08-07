package com.osrstcgbattles.engine;

import java.util.Objects;

/** One specific unit on the board, separate from its reusable card definition. */
public final class BoardUnit
{
	private final String instanceId;
	private final UnitCard definition;
	private final int currentAttack;
	private final int currentHealth;
	private final boolean ready;

	BoardUnit(String instanceId, UnitCard definition, int currentAttack, int currentHealth, boolean ready)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.definition = Objects.requireNonNull(definition, "definition");
		this.currentAttack = currentAttack;
		this.currentHealth = currentHealth;
		this.ready = ready;
	}

	static BoardUnit deploy(String instanceId, UnitCard definition)
	{
		return new BoardUnit(instanceId, definition, definition.getBaseAttack(), definition.getBaseHealth(), false);
	}

	public String getInstanceId()
	{
		return instanceId;
	}

	public UnitCard getDefinition()
	{
		return definition;
	}

	public int getCurrentAttack()
	{
		return currentAttack;
	}

	public int getCurrentHealth()
	{
		return currentHealth;
	}

	public boolean isReady()
	{
		return ready;
	}

	BoardUnit withStats(int attack, int health)
	{
		return new BoardUnit(instanceId, definition, attack, health, ready);
	}

	BoardUnit withReady(boolean value)
	{
		return new BoardUnit(instanceId, definition, currentAttack, currentHealth, value);
	}

	/** @deprecated Use {@link #getCurrentAttack()}. */
	@Deprecated
	public int getCurrentPower()
	{
		return currentAttack;
	}

	@Override
	public String toString()
	{
		return definition.getName() + " (" + currentAttack + "/" + currentHealth + ")";
	}
}
