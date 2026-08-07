package com.osrstcgbattles.ui;

import java.awt.image.BufferedImage;

/** Cached party identity copied at battle creation, without retaining a mutable PartyMember. */
public final class PartyBattleParticipant
{
	private static final String UNKNOWN = "<unknown>";

	private final long memberId;
	private final String displayName;
	private final BufferedImage avatar;

	public PartyBattleParticipant(long memberId, String partyDisplayName, String knownDisplayName,
		BufferedImage avatar)
	{
		this.memberId = memberId;
		this.displayName = usable(partyDisplayName) ? partyDisplayName.trim()
			: usable(knownDisplayName) ? knownDisplayName.trim() : null;
		this.avatar = avatar;
	}

	public long getMemberId()
	{
		return memberId;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public BufferedImage getAvatar()
	{
		return avatar;
	}

	private static boolean usable(String value)
	{
		return value != null && !value.trim().isEmpty() && !UNKNOWN.equalsIgnoreCase(value.trim());
	}
}
