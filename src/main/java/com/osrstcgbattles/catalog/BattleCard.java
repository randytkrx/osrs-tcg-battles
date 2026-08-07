package com.osrstcgbattles.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BattleCard
{
	private final String id;
	private final String azCardName;
	private final String displayName;
	private final CardCategory category;
	private final Faction faction;
	private final Rarity rarity;
	private final int manaCost;
	private final int attack;
	private final int health;
	private final Set<String> tags;
	private final String rulesText;
	private final List<CardAbility> abilities;

	BattleCard(String id, String azCardName, String displayName, CardCategory category, Faction faction,
		Rarity rarity, int manaCost, int attack, int health, Set<String> tags,
		String rulesText, List<CardAbility> abilities)
	{
		this.id = id;
		this.azCardName = azCardName;
		this.displayName = displayName;
		this.category = category;
		this.faction = faction;
		this.rarity = rarity;
		this.manaCost = manaCost;
		this.attack = attack;
		this.health = health;
		this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
		this.rulesText = rulesText;
		this.abilities = Collections.unmodifiableList(new ArrayList<>(abilities));
	}

	public String getId() { return id; }
	public String getAzCardName() { return azCardName; }
	public String getDisplayName() { return displayName; }
	public CardCategory getCategory() { return category; }
	public Faction getFaction() { return faction; }
	public Rarity getRarity() { return rarity; }
	public int getManaCost() { return manaCost; }
	public int getAttack() { return attack; }
	public int getHealth() { return health; }
	/** @deprecated Use {@link #getManaCost()}. */
	@Deprecated public int getProvisionCost() { return manaCost; }
	/** @deprecated Use {@link #getAttack()}. */
	@Deprecated public int getUnitPower() { return attack; }
	public Set<String> getTags() { return tags; }
	public String getRulesText() { return rulesText; }
	public List<CardAbility> getAbilities() { return abilities; }
}
