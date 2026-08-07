package com.osrstcgbattles.engine;

import java.util.Objects;

/** A catalog-independent effect applied when a unit is deployed. */
public final class DeployEffect
{
	public enum Type
	{
		BOOST,
		DAMAGE
	}

	public enum Target
	{
		SELF,
		ALLIED_UNIT,
		ENEMY_UNIT
	}

	private final Type type;
	private final Target target;
	private final int amount;

	public DeployEffect(Type type, Target target, int amount)
	{
		this.type = Objects.requireNonNull(type, "type");
		this.target = Objects.requireNonNull(target, "target");
		if (amount <= 0)
		{
			throw new IllegalArgumentException("amount must be positive");
		}
		if (type == Type.BOOST && target == Target.ENEMY_UNIT
			|| type == Type.DAMAGE && target != Target.ENEMY_UNIT)
		{
			throw new IllegalArgumentException("target is not valid for " + type);
		}
		this.amount = amount;
	}

	public Type getType()
	{
		return type;
	}

	public Target getTarget()
	{
		return target;
	}

	public int getAmount()
	{
		return amount;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof DeployEffect))
		{
			return false;
		}
		DeployEffect effect = (DeployEffect) other;
		return amount == effect.amount && type == effect.type && target == effect.target;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(type, target, amount);
	}
}
