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
import com.osrstcgbattles.engine.DeathrattleEffect;
import com.osrstcgbattles.engine.DeployEffect;
import com.osrstcgbattles.engine.SpecialCard;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.engine.UnitKeyword;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class CatalogDeckFactory
{
	private static final int DEFAULT_DEMO_SIZE = 30;

	private final BattleCardCatalog catalog;
	private final Random random;

	public CatalogDeckFactory(BattleCardCatalog catalog)
	{
		this(catalog, new Random());
	}

	CatalogDeckFactory(BattleCardCatalog catalog, Random random)
	{
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.random = Objects.requireNonNull(random, "random");
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

	public Card createCard(String cardId)
	{
		BattleCard definition = catalog.findById(Objects.requireNonNull(cardId, "cardId"))
			.orElseThrow(() -> new IllegalArgumentException("Unknown catalog card ID: " + cardId));
		return toCard(definition);
	}

	public UnitCard createBoardUnitCard(String cardId)
	{
		BattleCard definition = catalog.findBoardCardById(Objects.requireNonNull(cardId, "cardId"))
			.orElseThrow(() -> new IllegalArgumentException("Unknown board card ID: " + cardId));
		if (definition.getCategory() != CardCategory.UNIT)
			throw new IllegalArgumentException("Board card is not a unit: " + cardId);
		return toUnitCard(definition);
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

		List<BattleCard> available = new ArrayList<>();
		for (BattleCard card : units)
		{
			int limit = card.getRarity() == Rarity.LEGENDARY ? 1 : 2;
			for (int i = 0; i < limit; i++)
			{
				available.add(card);
			}
		}
		if (available.size() < size)
		{
			throw new IllegalStateException("Catalog cannot fill a " + size + "-card random deck");
		}
		Collections.shuffle(available, random);

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (BattleCard card : available.subList(0, size))
		{
			counts.put(card.getId(), counts.getOrDefault(card.getId(), 0) + 1);
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
			card.getHealth(), mapEffects(card), mapKeywords(card), mapDeathrattles(card));
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
			if (ability.getType() == AbilityType.VANILLA || isKeyword(ability.getType())
				|| isDeathrattle(ability.getType()))
			{
				continue;
			}
			DeployEffect.Type type = ability.getType() == AbilityType.DEPLOY_BOOST
				? DeployEffect.Type.BOOST : ability.getType() == AbilityType.DEPLOY_DAMAGE
					? DeployEffect.Type.DAMAGE : DeployEffect.Type.MANA;
			DeployEffect.Target target = ability.getType() == AbilityType.GAIN_MANA
				? DeployEffect.Target.HERO : DeployEffect.Target.valueOf(ability.getStringParams().get("target"));
			effects.add(new DeployEffect(type, target, ability.getNumericParams().get("amount")));
		}
		return effects;
	}

	private static EnumSet<UnitKeyword> mapKeywords(BattleCard card)
	{
		EnumSet<UnitKeyword> keywords = EnumSet.noneOf(UnitKeyword.class);
		for (CardAbility ability : card.getAbilities())
		{
			switch (ability.getType())
			{
				case SHIELD: keywords.add(UnitKeyword.SHIELD); break;
				case LIFESTEAL: keywords.add(UnitKeyword.LIFESTEAL); break;
				case POISONOUS: keywords.add(UnitKeyword.POISONOUS); break;
				case RUSH: keywords.add(UnitKeyword.RUSH); break;
				case TAUNT: keywords.add(UnitKeyword.TAUNT); break;
				case STEALTH: keywords.add(UnitKeyword.STEALTH); break;
				case NEX_ASCENSION: keywords.add(UnitKeyword.NEX_ASCENSION); break;
				default: break;
			}
		}
		return keywords;
	}

	private static List<DeathrattleEffect> mapDeathrattles(BattleCard card)
	{
		List<DeathrattleEffect> effects = new ArrayList<>();
		for (CardAbility ability : card.getAbilities())
		{
			DeathrattleEffect.Type type;
			switch (ability.getType())
			{
				case DEATHRATTLE_DAMAGE_HERO: type = DeathrattleEffect.Type.DAMAGE_ENEMY_HERO; break;
				case DEATHRATTLE_DRAW: type = DeathrattleEffect.Type.DRAW_CARD; break;
				case DEATHRATTLE_SUMMON: type = DeathrattleEffect.Type.SUMMON_SPIRIT; break;
				default: continue;
			}
			effects.add(new DeathrattleEffect(type, ability.getNumericParams().get("amount")));
		}
		return effects;
	}

	private static boolean isKeyword(AbilityType type)
	{
		return type == AbilityType.SHIELD || type == AbilityType.LIFESTEAL || type == AbilityType.POISONOUS
			|| type == AbilityType.RUSH || type == AbilityType.TAUNT || type == AbilityType.STEALTH
			|| type == AbilityType.NEX_ASCENSION;
	}

	private static boolean isDeathrattle(AbilityType type)
	{
		return type == AbilityType.DEATHRATTLE_DAMAGE_HERO || type == AbilityType.DEATHRATTLE_DRAW
			|| type == AbilityType.DEATHRATTLE_SUMMON;
	}
}
