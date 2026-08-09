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
	private final boolean shielded;
	private final boolean stealthed;
	private final boolean rushRestricted;

	BoardUnit(String instanceId, UnitCard definition, int currentAttack, int currentHealth, boolean ready,
		boolean shielded, boolean stealthed, boolean rushRestricted)
	{
		this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
		this.definition = Objects.requireNonNull(definition, "definition");
		this.currentAttack = currentAttack;
		this.currentHealth = currentHealth;
		this.ready = ready;
		this.shielded = shielded;
		this.stealthed = stealthed;
		this.rushRestricted = rushRestricted;
	}

	static BoardUnit deploy(String instanceId, UnitCard definition)
	{
		boolean rush = definition.hasKeyword(UnitKeyword.RUSH);
		return new BoardUnit(instanceId, definition, definition.getBaseAttack(), definition.getBaseHealth(), rush,
			definition.hasKeyword(UnitKeyword.SHIELD), definition.hasKeyword(UnitKeyword.STEALTH), rush);
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

	public boolean isShielded() { return shielded; }
	public boolean isStealthed() { return stealthed; }
	public boolean isRushRestricted() { return rushRestricted; }
	public boolean hasKeyword(UnitKeyword keyword) { return definition.hasKeyword(keyword); }

	BoardUnit withStats(int attack, int health)
	{
		return new BoardUnit(instanceId, definition, attack, health, ready, shielded, stealthed, rushRestricted);
	}

	BoardUnit withReady(boolean value)
	{
		return new BoardUnit(instanceId, definition, currentAttack, currentHealth, value, shielded, stealthed,
			value ? false : rushRestricted);
	}

	BoardUnit withoutShield()
	{
		return new BoardUnit(instanceId, definition, currentAttack, currentHealth, ready, false, stealthed,
			rushRestricted);
	}

	BoardUnit reveal()
	{
		return new BoardUnit(instanceId, definition, currentAttack, currentHealth, ready, shielded, false,
			rushRestricted);
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
