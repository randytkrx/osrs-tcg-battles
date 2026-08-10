package com.osrstcgbattles.integration;

import com.google.gson.Gson;
import com.osrstcgbattles.catalog.AbilityType;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.catalog.CardAbility;
import com.osrstcgbattles.catalog.CardCategory;
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
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CatalogCardContractTest
{
	@Test
	public void convertsAllCardsAndEveryDeclaredAbilityExactly()
	{
		BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog);
		Set<AbilityType> coverage = EnumSet.noneOf(AbilityType.class);
		assertEquals(400, catalog.getCards().size());

		for (BattleCard definition : catalog.getCards())
		{
			Card card = factory.create(new Deck("test", "Test",
				Collections.singletonList(new DeckEntry(definition.getId(), 1)))).get(0);
			String message = definition.getId();
			assertEquals(message, definition.getId(), card.getId());
			assertEquals(message, definition.getDisplayName(), card.getName());
			assertEquals(message, definition.getManaCost(), card.getManaCost());
			for (CardAbility ability : definition.getAbilities()) coverage.add(ability.getType());

			List<DeployEffect> deployEffects = deployEffects(definition);
			if (definition.getCategory() == CardCategory.SPECIAL)
			{
				assertTrue(message, card instanceof SpecialCard);
				assertEquals(message, deployEffects, ((SpecialCard) card).getDeployEffects());
				continue;
			}

			assertTrue(message, card instanceof UnitCard);
			UnitCard unit = (UnitCard) card;
			assertEquals(message, definition.getAttack(), unit.getBaseAttack());
			assertEquals(message, definition.getHealth(), unit.getBaseHealth());
			assertEquals(message, deployEffects, unit.getDeployEffects());
			assertEquals(message, keywords(definition), unit.getKeywords());
			assertEquals(message, deathrattles(definition), unit.getDeathrattles());
		}

		assertEquals(EnumSet.allOf(AbilityType.class), coverage);
	}

	private static List<DeployEffect> deployEffects(BattleCard card)
	{
		List<DeployEffect> effects = new ArrayList<>();
		for (CardAbility ability : card.getAbilities())
		{
			if (ability.getType() == AbilityType.DEPLOY_BOOST)
			{
				effects.add(new DeployEffect(DeployEffect.Type.BOOST,
					DeployEffect.Target.valueOf(ability.getStringParams().get("target")), amount(ability)));
			}
			else if (ability.getType() == AbilityType.DEPLOY_DAMAGE)
			{
				effects.add(new DeployEffect(DeployEffect.Type.DAMAGE,
					DeployEffect.Target.valueOf(ability.getStringParams().get("target")), amount(ability)));
			}
			else if (ability.getType() == AbilityType.GAIN_MANA)
			{
				effects.add(new DeployEffect(DeployEffect.Type.MANA, DeployEffect.Target.HERO, amount(ability)));
			}
		}
		return effects;
	}

	private static Set<UnitKeyword> keywords(BattleCard card)
	{
		Set<UnitKeyword> keywords = EnumSet.noneOf(UnitKeyword.class);
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

	private static List<DeathrattleEffect> deathrattles(BattleCard card)
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
			effects.add(new DeathrattleEffect(type, amount(ability)));
		}
		return effects;
	}

	private static int amount(CardAbility ability)
	{
		return ability.getNumericParams().get("amount");
	}
}
