package com.osrstcgbattles.ui.board;

import java.awt.Image;

/** Immutable identity rendered for one engine seat. */
public final class BattlePlayerIdentity
{
	private final String displayName;
	private final Image avatar;

	public BattlePlayerIdentity(String displayName, Image avatar, String fallbackLabel)
	{
		this.displayName = normalize(displayName, fallbackLabel);
		this.avatar = avatar;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public Image getAvatar()
	{
		return avatar;
	}

	public BattlePlayerIdentity withAvatar(Image avatar)
	{
		return new BattlePlayerIdentity(displayName, avatar, displayName);
	}

	static String normalize(String displayName, String fallbackLabel)
	{
		if (displayName != null && !displayName.trim().isEmpty())
		{
			return displayName.trim();
		}
		return fallbackLabel == null || fallbackLabel.trim().isEmpty() ? "Player" : fallbackLabel.trim();
	}
}
