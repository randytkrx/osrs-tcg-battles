package com.osrstcgbattles.party;

public interface BattlePartyListener
{
	void onPartyDuelSnapshotChanged(PartyDuelSnapshot snapshot);

	default void onPartyApplicationMessage(PartyApplicationMessage message)
	{
	}

	default void onPartyOperationFailed(String message)
	{
	}
}
