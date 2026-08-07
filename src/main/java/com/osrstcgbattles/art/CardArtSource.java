package com.osrstcgbattles.art;

import java.util.Objects;

/** Where a card's picture comes from. */
public final class CardArtSource
{
	public enum Kind
	{
		ITEM, NPC
	}

	private final Kind kind;
	private final String imageUrl;

	CardArtSource(Kind kind, String imageUrl)
	{
		this.kind = Objects.requireNonNull(kind, "kind");
		this.imageUrl = imageUrl == null ? "" : imageUrl;
	}

	public Kind getKind()
	{
		return kind;
	}

	/** The wiki URL for NPC art; empty for item art, which resolves through ItemManager. */
	public String getImageUrl()
	{
		return imageUrl;
	}
}
