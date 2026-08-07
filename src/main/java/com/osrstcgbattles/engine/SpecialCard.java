package com.osrstcgbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An immutable card that resolves its effects without entering the board. */
public final class SpecialCard implements Card
{
	private final String id;
	private final String name;
	private final int manaCost;
	private final List<DeployEffect> deployEffects;

	public SpecialCard(String id, String name, int manaCost, List<DeployEffect> deployEffects)
	{
		this.id = requireText(id, "id");
		this.name = requireText(name, "name");
		if (manaCost < 0 || manaCost > 10)
		{
			throw new IllegalArgumentException("manaCost must be between 0 and 10");
		}
		this.manaCost = manaCost;
		Objects.requireNonNull(deployEffects, "deployEffects");
		List<DeployEffect> effects = new ArrayList<>(deployEffects.size());
		for (DeployEffect effect : deployEffects)
		{
			effect = Objects.requireNonNull(effect, "deployEffects contains null");
			if (effect.getTarget() == DeployEffect.Target.SELF)
			{
				throw new IllegalArgumentException("Special cards cannot target self");
			}
			effects.add(effect);
		}
		this.deployEffects = Collections.unmodifiableList(effects);
	}

	private static String requireText(String value, String field)
	{
		Objects.requireNonNull(value, field);
		if (value.trim().isEmpty())
		{
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	@Override
	public String getId()
	{
		return id;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public int getManaCost()
	{
		return manaCost;
	}

	public List<DeployEffect> getDeployEffects()
	{
		return deployEffects;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof SpecialCard))
		{
			return false;
		}
		SpecialCard card = (SpecialCard) other;
		return manaCost == card.manaCost && id.equals(card.id) && name.equals(card.name)
			&& deployEffects.equals(card.deployEffects);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, manaCost, deployEffects);
	}

	@Override
	public String toString()
	{
		return name + " (" + manaCost + ", Special)";
	}
}
