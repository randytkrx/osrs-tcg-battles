package com.osrstcgbattles.engine;

import java.util.Collections;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class UnitCard implements Card
{
	private final String id;
	private final String name;
	private final int manaCost;
	private final int baseAttack;
	private final int baseHealth;
	private final List<DeployEffect> deployEffects;
	private final Set<UnitKeyword> keywords;
	private final List<DeathrattleEffect> deathrattles;

	public UnitCard(String id, String name, int manaCost, int baseAttack, int baseHealth)
	{
		this(id, name, manaCost, baseAttack, baseHealth, Collections.emptyList());
	}

	public UnitCard(String id, String name, int manaCost, int baseAttack, int baseHealth,
		List<DeployEffect> deployEffects)
	{
		this(id, name, manaCost, baseAttack, baseHealth, deployEffects,
			Collections.emptySet(), Collections.emptyList());
	}

	public UnitCard(String id, String name, int manaCost, int baseAttack, int baseHealth,
		List<DeployEffect> deployEffects, Set<UnitKeyword> keywords, List<DeathrattleEffect> deathrattles)
	{
		this.id = requireText(id, "id");
		this.name = requireText(name, "name");
		if (manaCost < 0 || manaCost > 10)
		{
			throw new IllegalArgumentException("manaCost must be between 0 and 10");
		}
		if (baseAttack < 0 || baseHealth <= 0)
		{
			throw new IllegalArgumentException("Unit attack must not be negative and health must be positive");
		}
		this.manaCost = manaCost;
		this.baseAttack = baseAttack;
		this.baseHealth = baseHealth;
		Objects.requireNonNull(deployEffects, "deployEffects");
		List<DeployEffect> effects = new ArrayList<>(deployEffects.size());
		for (DeployEffect effect : deployEffects)
		{
			effects.add(Objects.requireNonNull(effect, "deployEffects contains null"));
		}
		this.deployEffects = Collections.unmodifiableList(effects);
		Objects.requireNonNull(keywords, "keywords");
		EnumSet<UnitKeyword> keywordCopy = EnumSet.noneOf(UnitKeyword.class);
		for (UnitKeyword keyword : keywords)
		{
			keywordCopy.add(Objects.requireNonNull(keyword, "keywords contains null"));
		}
		this.keywords = Collections.unmodifiableSet(keywordCopy);
		Objects.requireNonNull(deathrattles, "deathrattles");
		List<DeathrattleEffect> deathrattleCopy = new ArrayList<>(deathrattles.size());
		for (DeathrattleEffect effect : deathrattles)
		{
			deathrattleCopy.add(Objects.requireNonNull(effect, "deathrattles contains null"));
		}
		this.deathrattles = Collections.unmodifiableList(deathrattleCopy);
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

	public int getBaseAttack()
	{
		return baseAttack;
	}

	public int getBaseHealth()
	{
		return baseHealth;
	}

	public List<DeployEffect> getDeployEffects()
	{
		return deployEffects;
	}

	public Set<UnitKeyword> getKeywords() { return keywords; }
	public boolean hasKeyword(UnitKeyword keyword) { return keywords.contains(keyword); }
	public List<DeathrattleEffect> getDeathrattles() { return deathrattles; }

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof UnitCard))
		{
			return false;
		}
		UnitCard card = (UnitCard) other;
		return manaCost == card.manaCost && baseAttack == card.baseAttack && baseHealth == card.baseHealth
			&& id.equals(card.id) && name.equals(card.name) && deployEffects.equals(card.deployEffects)
			&& keywords.equals(card.keywords) && deathrattles.equals(card.deathrattles);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, manaCost, baseAttack, baseHealth, deployEffects, keywords, deathrattles);
	}

	@Override
	public String toString()
	{
		return name + " (" + manaCost + ", " + baseAttack + "/" + baseHealth + ")";
	}
}
