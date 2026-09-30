package com.osrstcgbattles;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OsrsTcgBattlesConfig.GROUP)
public interface OsrsTcgBattlesConfig extends Config
{
	String GROUP = "osrsTcgBattles";

	@ConfigItem(
		keyName = "enableOnlinePlay",
		name = "Enable online play",
		description = "Connect to the independent Duelscape matchmaking service.",
		warning = "Online play sends your OSRS display name, public device key, selected deck, battle actions, and IP address to an independent third-party server. Ranked identity, rating, and match history are retained."
	)
	default boolean enableOnlinePlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onlineServerUrl",
		name = "Online server",
		description = "Secure WebSocket endpoint used for online matchmaking."
	)
	default String onlineServerUrl()
	{
		return "wss://play.deargod.live/v1/ws";
	}
}
