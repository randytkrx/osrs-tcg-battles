package com.osrstcgbattles.engine;

import java.util.Objects;

public final class DeathrattleEffect
{
	public enum Type
	{
		DAMAGE_ENEMY_HERO,
		DRAW_CARD,
		SUMMON_SPIRIT
	}

	private final Type type;
	private final int amount;

	public DeathrattleEffect(Type type, int amount)
	{
		this.type = Objects.requireNonNull(type, "type");
		if (amount < 1 || amount > 5) throw new IllegalArgumentException("amount must be between 1 and 5");
		this.amount = amount;
	}

	public Type getType() { return type; }
	public int getAmount() { return amount; }

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof DeathrattleEffect)) return false;
		DeathrattleEffect effect = (DeathrattleEffect) other;
		return type == effect.type && amount == effect.amount;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(type, amount);
	}
}
