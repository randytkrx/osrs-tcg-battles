package com.osrstcgbattles.integration;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.CardCategory;
import com.osrstcgbattles.catalog.CardAbility;
import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.Rarity;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.SpecialCard;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.engine.DeployEffect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class CatalogDeckFactory
{
	private static final int DEFAULT_DEMO_SIZE = 30;

	private final BattleCardCatalog catalog;

	public CatalogDeckFactory(BattleCardCatalog catalog)
	{
		this.catalog = Objects.requireNonNull(catalog, "catalog");
	}

	public List<Card> create(Deck deck)
	{
		Objects.requireNonNull(deck, "deck");
		List<Card> cards = new ArrayList<>();
		for (DeckEntry entry : deck.getEntries())
		{
			if (entry.getCount() <= 0)
			{
				throw new IllegalArgumentException("Deck entry count must be positive for card: " + entry.getCardId());
			}
			BattleCard definition = catalog.findById(entry.getCardId())
				.orElseThrow(() -> new IllegalArgumentException("Unknown catalog card ID: " + entry.getCardId()));
			for (int i = 0; i < entry.getCount(); i++)
			{
				cards.add(toCard(definition));
			}
		}
		return Collections.unmodifiableList(cards);
	}

	public List<UnitCard> demoDeck()
	{
		return demoDeck(DEFAULT_DEMO_SIZE);
	}

	public List<UnitCard> demoDeck(int minimumSize)
	{
		if (minimumSize <= 0)
		{
			throw new IllegalArgumentException("minimumSize must be positive");
		}
		List<BattleCard> units = new ArrayList<>();
		for (BattleCard card : catalog.getCards())
		{
			if (card.getCategory() == CardCategory.UNIT)
			{
				units.add(card);
			}
		}
		if (units.isEmpty())
		{
			throw new IllegalStateException("Catalog contains no unit cards for a demo deck");
		}

		List<UnitCard> deck = new ArrayList<>(minimumSize);
		for (int i = 0; i < minimumSize; i++)
		{
			deck.add(toUnitCard(units.get(i % units.size())));
		}
		return Collections.unmodifiableList(deck);
	}

	/** Builds a random, validator-passing deck of exactly {@code size} cards from the catalog units. */
	public Deck randomDeck()
	{
		return randomDeck(DEFAULT_DEMO_SIZE);
	}

	public Deck randomDeck(int size)
	{
		if (size <= 0)
		{
			throw new IllegalArgumentException("size must be positive");
		}
		List<BattleCard> units = new ArrayList<>();
		for (BattleCard card : catalog.getCards())
		{
			if (card.getCategory() == CardCategory.UNIT)
			{
				units.add(card);
			}
		}
		if (units.isEmpty())
		{
			throw new IllegalStateException("Catalog contains no unit cards for a random deck");
		}

		Map<String, Integer> counts = new LinkedHashMap<>();
		int added = 0;
		int cursor = 0;
		int guard = 0;
		Random random = new Random();
		while (added < size)
		{
			BattleCard card = units.get(cursor);
			int limit = card.getRarity() == Rarity.LEGENDARY ? 1 : 2;
			int current = counts.getOrDefault(card.getId(), 0);
			if (current < limit)
			{
				counts.put(card.getId(), current + 1);
				added++;
			}
			cursor++;
			if (cursor >= units.size())
			{
				cursor = 0;
				Collections.shuffle(units, random);
			}
			if (++guard > size * 3 + units.size())
			{
				throw new IllegalStateException("Catalog cannot fill a " + size + "-card random deck");
			}
		}

		List<DeckEntry> entries = new ArrayList<>(counts.size());
		for (Map.Entry<String, Integer> entry : counts.entrySet())
		{
			entries.add(new DeckEntry(entry.getKey(), entry.getValue()));
		}
		return new Deck("random-" + Integer.toHexString(catalog.getSha256().hashCode() & 0xffffff), "Random Deck", entries);
	}

	private static UnitCard toUnitCard(BattleCard card)
	{
		return new UnitCard(card.getId(), card.getDisplayName(), card.getManaCost(), card.getAttack(),
			card.getHealth(), mapEffects(card));
	}

	private static Card toCard(BattleCard card)
	{
		return card.getCategory() == CardCategory.UNIT ? toUnitCard(card)
			: new SpecialCard(card.getId(), card.getDisplayName(), card.getManaCost(), mapEffects(card));
	}

	private static List<DeployEffect> mapEffects(BattleCard card)
	{
		List<DeployEffect> effects = new ArrayList<>();
		for (CardAbility ability : card.getAbilities())
		{
			if (ability.getType() == AbilityType.VANILLA)
			{
				continue;
			}
			DeployEffect.Type type = ability.getType() == AbilityType.DEPLOY_BOOST
				? DeployEffect.Type.BOOST : DeployEffect.Type.DAMAGE;
			DeployEffect.Target target = DeployEffect.Target.valueOf(
				ability.getStringParams().get("target"));
			effects.add(new DeployEffect(type, target, ability.getNumericParams().get("amount")));
		}
		return effects;
	}
}
