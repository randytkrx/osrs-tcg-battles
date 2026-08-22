package com.osrstcgbattles.ui;

import com.osrstcgbattles.engine.DuelscapeEngine;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OsrsTcgBattlesPanelTest
{
	@Test
	public void howToPlayReflectsEngineRules()
	{
		String rules = OsrsTcgBattlesPanel.rulesHtml();
		assertTrue(rules.contains(DuelscapeEngine.DECK_SIZE + "-card deck"));
		assertTrue(rules.contains(DuelscapeEngine.HERO_HEALTH + " health"));
		assertTrue(rules.contains("summoning sickness"));
		assertTrue(rules.contains("fatigue"));
		assertTrue(rules.contains("Hover any card"));
		assertTrue(rules.contains("OSRS TCG"));
		assertTrue(rules.contains("open card packs"));
		assertTrue(rules.contains("drag the unit"));
	}
}
