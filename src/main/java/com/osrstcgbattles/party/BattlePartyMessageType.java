package com.osrstcgbattles.party;

public enum BattlePartyMessageType
{
	INVITE,
	INVITE_ACK,
	ACCEPT,
	DECLINE,
	KEY,
	READY,
	ACTION,
	ACTION_ACK,
	/** Initial seed commitment and deck setup exchange; not live-state recovery. */
	SNAPSHOT,
	ABORT
}
