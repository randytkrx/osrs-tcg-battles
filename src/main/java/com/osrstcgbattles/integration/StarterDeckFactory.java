package com.osrstcgbattles.integration;

import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.CardAbility;
import com.osrstcgbattles.catalog.Rarity;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.engine.GwentEngine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Deterministic built-in decks. Exact definitions are playable without collection ownership. */
public final class StarterDeckFactory
{
	public static final String DEATHRATTLE_ID = "starter-deathrattle-value";
	public static final String RUSH_ID = "starter-rush-swarm";
	public static final String BULWARK_ID = "starter-gielinor-bulwark";

	private final List<BattleCard> cards;

	public StarterDeckFactory(BattleCardCatalog catalog)
	{
		Objects.requireNonNull(catalog, "catalog");
		cards = new ArrayList<>(catalog.getCards());
		cards.sort(Comparator.comparingInt(BattleCard::getManaCost)
			.thenComparing(BattleCard::getId));
	}

	public List<Deck> createStarterDecks()
	{
		List<Deck> starters = new ArrayList<>();
		starters.add(deathrattleValue());
		starters.add(rushSwarm());
		starters.add(gielinorBulwark());
		return Collections.unmodifiableList(starters);
	}

	public boolean isUnmodifiedStarter(Deck deck)
	{
		return deck != null && createStarterDecks().contains(deck);
	}

	private Deck deathrattleValue()
	{
		Builder deck = new Builder(DEATHRATTLE_ID, "Starter: Deathrattle Value");
		deck.add(has(AbilityType.GAIN_MANA), 5);
		deck.add(has(AbilityType.DEATHRATTLE_DRAW), 4);
		deck.add(has(AbilityType.DEATHRATTLE_SUMMON), 4);
		deck.add(has(AbilityType.DEATHRATTLE_DAMAGE_HERO), 4);
		deck.add(and(has(AbilityType.SHIELD), costAtMost(3)), 4);
		deck.add(and(has(AbilityType.LIFESTEAL), costAtMost(4)), 4);
		deck.add(and(has(AbilityType.TAUNT), costAtMost(5)), 3);
		deck.add(and(has(AbilityType.VANILLA), costAtMost(3)), 2);
		return deck.finish();
	}

	private Deck rushSwarm()
	{
		Builder deck = new Builder(RUSH_ID, "Starter: Rush Swarm");
		deck.add(has(AbilityType.GAIN_MANA), 5);
		deck.add(has(AbilityType.RUSH), 10);
		deck.add(and(has(AbilityType.POISONOUS), costAtMost(4)), 4);
		deck.add(and(has(AbilityType.STEALTH), costAtMost(4)), 3);
		deck.add(and(has(AbilityType.DEPLOY_DAMAGE), costAtMost(4)), 4);
		deck.add(and(has(AbilityType.VANILLA), costAtMost(3)), 4);
		return deck.finish();
	}

	private Deck gielinorBulwark()
	{
		Builder deck = new Builder(BULWARK_ID, "Starter: Gielinor Bulwark");
		deck.add(has(AbilityType.SHIELD), 8);
		deck.add(has(AbilityType.LIFESTEAL), 6);
		deck.add(has(AbilityType.TAUNT), 8);
		deck.add(has(AbilityType.DEPLOY_BOOST), 4);
		deck.add(has(AbilityType.DEATHRATTLE_DRAW), 2);
		deck.add(has(AbilityType.GAIN_MANA), 2);
		return deck.finish();
	}

	private static Predicate<BattleCard> has(AbilityType type)
	{
		return card -> {
			for (CardAbility ability : card.getAbilities()) if (ability.getType() == type) return true;
			return false;
		};
	}

	private static Predicate<BattleCard> costAtMost(int mana)
	{
		return card -> card.getManaCost() <= mana;
	}

	private static Predicate<BattleCard> and(Predicate<BattleCard> first, Predicate<BattleCard> second)
	{
		return first.and(second);
	}

	private final class Builder
	{
		private final String id;
		private final String name;
		private final Map<String, Integer> quantities = new LinkedHashMap<>();
		private int size;

		private Builder(String id, String name)
		{
			this.id = id;
			this.name = name;
		}

		private void add(Predicate<BattleCard> predicate, int requested)
		{
			int target = Math.min(GwentEngine.DECK_SIZE, size + requested);
			for (BattleCard card : cards)
			{
				if (size >= target) break;
				if (!predicate.test(card) || card.getId().equals("asgarnia-nex")) continue;
				int limit = card.getRarity() == Rarity.LEGENDARY ? 1 : 2;
				int current = quantities.getOrDefault(card.getId(), 0);
				int add = Math.min(limit - current, target - size);
				if (add > 0)
				{
					quantities.put(card.getId(), current + add);
					size += add;
				}
			}
		}

		private Deck finish()
		{
			add(card -> !card.getId().equals("asgarnia-nex"), GwentEngine.DECK_SIZE - size);
			if (size != GwentEngine.DECK_SIZE)
				throw new IllegalStateException("Unable to build starter deck " + name);
			List<DeckEntry> entries = new ArrayList<>();
			for (Map.Entry<String, Integer> entry : quantities.entrySet())
				entries.add(new DeckEntry(entry.getKey(), entry.getValue()));
			return new Deck(id, name, entries);
		}
	}
}
