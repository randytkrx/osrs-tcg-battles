package com.osrstcgbattles.ui;

import com.osrstcgbattles.engine.GwentEngine;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OsrsTcgBattlesPanelTest
{
	@Test
	public void howToPlayReflectsEngineRules()
	{
		String rules = OsrsTcgBattlesPanel.rulesHtml();
		assertTrue(rules.contains(GwentEngine.DECK_SIZE + "-card deck"));
		assertTrue(rules.contains(GwentEngine.HERO_HEALTH + " health"));
		assertTrue(rules.contains("summoning sickness"));
		assertTrue(rules.contains("fatigue"));
		assertTrue(rules.contains("Hover any card"));
	}
}
