package com.osrstcgbattles.ui;

import com.google.gson.Gson;
import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattleCardTooltipsTest
{
	private final BattleCardCatalog catalog = new BattleCardCatalogLoader(new Gson()).loadDefault();

	@Test
	public void tooltipIncludesCardIdentityStatsAndRules()
	{
		BattleCard card = catalog.findById("misthalin-dark-wizard").get();
		String tooltip = BattleCardTooltips.format(card);

		assertTrue(tooltip.contains("Dark Wizard"));
		assertTrue(tooltip.contains("3 attack"));
		assertTrue(tooltip.contains("Deploy: Deal 2 damage to an enemy unit."));
	}

	@Test
	public void tooltipIncludesVanillaRules()
	{
		String tooltip = BattleCardTooltips.format(catalog.findById("neutral-chicken").get());
		assertTrue(tooltip.contains("No ability."));
	}

	@Test
	public void htmlValuesAreEscaped()
	{
		String escaped = BattleCardTooltips.escapeHtml("A&B <tag> \"quote\"");
		assertTrue(escaped.contains("A&amp;B"));
		assertTrue(escaped.contains("&lt;tag&gt;"));
		assertTrue(escaped.contains("&quot;quote&quot;"));
		assertFalse(escaped.contains("<tag>"));
	}
}
