package com.osrstcgbattles.party;

import java.util.Objects;

/** Immutable party-member choice suitable for UI presentation. */
public final class PartyOpponent
{
	private final long memberId;
	private final String displayName;

	public PartyOpponent(long memberId, String displayName)
	{
		if (memberId <= 0)
		{
			throw new IllegalArgumentException("memberId must be positive");
		}
		this.memberId = memberId;
		this.displayName = Objects.requireNonNull(displayName, "displayName");
	}

	public long getMemberId() { return memberId; }
	public String getDisplayName() { return displayName; }
	public boolean equals(Object other)
	{
		return other instanceof PartyOpponent && memberId == ((PartyOpponent) other).memberId
			&& displayName.equals(((PartyOpponent) other).displayName);
	}
	public int hashCode() { return Objects.hash(memberId, displayName); }
}
