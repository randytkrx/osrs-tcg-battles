package com.osrstcgbattles;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsTcgBattlesPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsTcgBattlesPlugin.class);
		RuneLite.main(args);
	}
}
