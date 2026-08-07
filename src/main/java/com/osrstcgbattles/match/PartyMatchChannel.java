package com.osrstcgbattles.match;

import com.osrstcgbattles.party.BattlePartyMessageType;
import java.util.Map;

/** Sends an application payload through the party transport's encrypted channel. */
public interface PartyMatchChannel
{
	boolean send(BattlePartyMessageType type, Map<String, ?> payload);
}
