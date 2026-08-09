package com.osrstcgbattles.integration;

import com.google.gson.Gson;
import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.engine.UnitKeyword;
import java.io.StringReader;
import java.util.Collections;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CatalogDeckFactoryTest
{
	@Test
	public void randomizesBeforeSelectingFirstCard()
	{
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog("LEGENDARY", "LEGENDARY", "LEGENDARY"),
			new Random()
			{
				@Override
				public int nextInt(int bound)
				{
					return 0;
				}
			});

		Deck deck = factory.randomDeck(1);

		assertEquals("card-b", deck.getEntries().get(0).getCardId());
	}

	@Test
	public void enforcesCopyLimitsAndRejectsInsufficientCapacity()
	{
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog("COMMON", "LEGENDARY"), new Random(1));
		Deck deck = factory.randomDeck(3);

		assertEquals(3, deck.getEntries().stream().mapToInt(entry -> entry.getCount()).sum());
		assertEquals(2, deck.getEntries().stream().filter(entry -> entry.getCardId().equals("card-a"))
			.findFirst().get().getCount());
		assertEquals(1, deck.getEntries().stream().filter(entry -> entry.getCardId().equals("card-b"))
			.findFirst().get().getCount());
		try
		{
			factory.randomDeck(4);
			fail("Expected insufficient capacity to fail");
		}
		catch (IllegalStateException error)
		{
			assertEquals("Catalog cannot fill a 4-card random deck", error.getMessage());
		}
	}

	@Test
	public void mapsCatalogKeywordsAndDeathrattlesIntoEngineCards()
	{
		BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();
		BattleCard shield = find(catalog, AbilityType.SHIELD);
		BattleCard deathrattle = find(catalog, AbilityType.DEATHRATTLE_SUMMON);

		UnitCard shieldCard = createOne(catalog, shield);
		UnitCard deathrattleCard = createOne(catalog, deathrattle);

		assertTrue(shieldCard.hasKeyword(UnitKeyword.SHIELD));
		assertEquals(1, deathrattleCard.getDeathrattles().size());
	}

	private static UnitCard createOne(BattleCardCatalog catalog, BattleCard card)
	{
		Deck deck = new Deck("one", "One", Collections.singletonList(new DeckEntry(card.getId(), 1)));
		return (UnitCard) new CatalogDeckFactory(catalog).create(deck).get(0);
	}

	private static BattleCard find(BattleCardCatalog catalog, AbilityType type)
	{
		return catalog.getCards().stream().filter(card -> card.getAbilities().stream()
			.anyMatch(ability -> ability.getType() == type)).findFirst().get();
	}

	private static BattleCardCatalog catalog(String... rarities)
	{
		StringBuilder cards = new StringBuilder();
		for (int i = 0; i < rarities.length; i++)
		{
			if (i > 0)
			{
				cards.append(',');
			}
			char suffix = (char) ('a' + i);
			cards.append("{\"id\":\"card-").append(suffix).append("\",\"azCardName\":\"Card ")
				.append(suffix).append("\",\"displayName\":\"Card ").append(suffix)
				.append("\",\"category\":\"UNIT\",\"faction\":\"NEUTRAL\",\"rarity\":\"")
				.append(rarities[i]).append("\",\"manaCost\":1,\"attack\":1,\"health\":1,")
				.append("\"tags\":[],\"rulesText\":\"None\",\"abilities\":[{\"type\":\"VANILLA\"}]}");
		}
		return new BattleCardCatalogLoader(new Gson()).load(new StringReader(
			"{\"catalogVersion\":2,\"rulesetVersion\":7,\"cards\":[" + cards + "]}"));
	}
}
