package com.osrstcgbattles.catalog;

import com.google.gson.Gson;
import com.osrstcgbattles.engine.DuelscapeEngine;
import java.io.StringReader;
import java.util.EnumSet;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BattleCardCatalogLoaderTest
{
	@Test
	public void exposesDeathrattleSpiritOnlyAsABoardToken()
	{
		BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();

		assertFalse(catalog.findById("deathrattle-spirit").isPresent());
		BattleCard spirit = catalog.findBoardCardById("deathrattle-spirit").get();
		assertEquals("Spirit", spirit.getDisplayName());
		assertEquals(1, spirit.getAttack());
		assertEquals(1, spirit.getHealth());
	}

	@Test
	public void rejectsEffectsRequiringDifferentCommandTargets()
	{
		try
		{
			load(effect("DEPLOY_BOOST", "ALLIED_UNIT") + "," + effect("DEPLOY_DAMAGE", "ENEMY_UNIT"));
			fail("Expected incompatible targets to fail");
		}
		catch (CatalogValidationException error)
		{
			assertTrue(error.getMessage().contains("incompatible command targets"));
		}
	}

	@Test
	public void permitsSelfAndNoTargetEffectsWithOneCommandTargetClass()
	{
		BattleCardCatalog catalog = load(effect("DEPLOY_BOOST", "SELF") + ","
			+ "{\"type\":\"VANILLA\"}," + effect("DEPLOY_DAMAGE", "ENEMY_UNIT"));

		assertEquals(3, catalog.getCards().get(0).getAbilities().size());
	}

	@Test
	public void defaultCatalogLoadsNoTargetManaCards()
	{
		BattleCard lightbearer = new BattleCardCatalogLoader(new Gson()).loadDefault().findById("neutral-lightbearer").get();

		assertEquals(0, lightbearer.getManaCost());
		assertEquals(AbilityType.GAIN_MANA, lightbearer.getAbilities().get(0).getType());
		assertEquals(Integer.valueOf(1), lightbearer.getAbilities().get(0).getNumericParams().get("amount"));
		assertTrue(lightbearer.getAbilities().get(0).getStringParams().isEmpty());
	}

	@Test
	public void defaultCatalogMatchesLoaderAndEngineRulesetVersions()
	{
		BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();

		assertEquals(BattleCardCatalogLoader.SUPPORTED_CATALOG_VERSION, catalog.getCatalogVersion());
		assertEquals(BattleCardCatalogLoader.SUPPORTED_RULESET_VERSION, catalog.getRulesetVersion());
		assertEquals(DuelscapeEngine.RULESET_VERSION, catalog.getRulesetVersion());
	}

	@Test
	public void expandedCatalogContainsEverySupportedArchetype()
	{
		BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();
		EnumSet<AbilityType> found = EnumSet.noneOf(AbilityType.class);
		for (BattleCard card : catalog.getCards())
			for (CardAbility ability : card.getAbilities()) found.add(ability.getType());

		assertEquals(400, catalog.getCards().size());
		for (AbilityType type : AbilityType.values()) assertTrue("Missing " + type, found.contains(type));
	}

	private static BattleCardCatalog load(String abilities)
	{
		String json = "{\"catalogVersion\":2,\"rulesetVersion\":8,\"cards\":[{"
			+ "\"id\":\"test-card\",\"azCardName\":\"Test Card\",\"displayName\":\"Test Card\","
			+ "\"category\":\"UNIT\",\"faction\":\"NEUTRAL\",\"rarity\":\"COMMON\","
			+ "\"manaCost\":1,\"attack\":1,\"health\":1,\"tags\":[],\"rulesText\":\"Test\","
			+ "\"abilities\":[" + abilities + "]}]}";
		return new BattleCardCatalogLoader(new Gson()).load(new StringReader(json));
	}

	private static String effect(String type, String target)
	{
		return "{\"type\":\"" + type + "\",\"numericParams\":{\"amount\":1},"
			+ "\"stringParams\":{\"target\":\"" + target + "\"}}";
	}
}
