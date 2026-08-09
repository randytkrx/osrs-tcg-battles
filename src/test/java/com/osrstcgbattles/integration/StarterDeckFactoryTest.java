package com.osrstcgbattles.integration;

import com.google.gson.Gson;
import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.collection.OwnedCardCollectionStatus;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidator;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StarterDeckFactoryTest
{
	private final BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();
	private final StarterDeckFactory factory = new StarterDeckFactory(catalog);

	@Test
	public void createsThreeValidThirtyCardDecks()
	{
		List<Deck> starters = factory.createStarterDecks();

		assertEquals(3, starters.size());
		for (Deck deck : starters)
		{
			assertEquals(30, deck.getEntries().stream().mapToInt(DeckEntry::getCount).sum());
			assertTrue(new DeckValidator().validate(deck, new CatalogCardLookup(catalog), Collections.emptySet(),
				OwnedCardCollectionStatus.UNKNOWN).isValid());
		}
	}

	@Test
	public void deckThemesContainTheirCoreMechanics()
	{
		List<Deck> starters = factory.createStarterDecks();

		assertTrue(count(starters.get(0), AbilityType.DEATHRATTLE_DAMAGE_HERO,
			AbilityType.DEATHRATTLE_DRAW, AbilityType.DEATHRATTLE_SUMMON) >= 10);
		assertTrue(count(starters.get(1), AbilityType.RUSH) >= 10);
		assertTrue(count(starters.get(2), AbilityType.SHIELD, AbilityType.LIFESTEAL, AbilityType.TAUNT) >= 20);
	}

	@Test
	public void ownershipExemptionRequiresTheExactBuiltInDefinition()
	{
		Deck starter = factory.createStarterDecks().get(0);

		assertTrue(factory.isUnmodifiedStarter(starter));
		assertFalse(factory.isUnmodifiedStarter(new Deck(starter.getId(), "Edited", starter.getEntries())));
	}

	private int count(Deck deck, AbilityType... types)
	{
		int count = 0;
		for (DeckEntry entry : deck.getEntries())
		{
			BattleCard card = catalog.findById(entry.getCardId()).get();
			for (AbilityType type : types)
			{
				if (card.getAbilities().stream().anyMatch(ability -> ability.getType() == type))
				{
					count += entry.getCount();
					break;
				}
			}
		}
		return count;
	}
}
