package com.osrstcgbattles.deck;

import java.util.Objects;

/** The card data needed to validate a deck, independent of the source card model. */
public final class CardInfo
{
	private final String id;
	private final String azCardName;
	private final String faction;
	private final String rarity;
	private final int manaCost;
	private final boolean unit;

	public CardInfo(String id, String azCardName, String faction, String rarity, int manaCost, boolean unit)
	{
		this.id = Objects.requireNonNull(id, "id");
		this.azCardName = Objects.requireNonNull(azCardName, "azCardName");
		this.faction = Objects.requireNonNull(faction, "faction");
		this.rarity = Objects.requireNonNull(rarity, "rarity");
		this.manaCost = manaCost;
		this.unit = unit;
	}

	public String getId()
	{
		return id;
	}

	public String getAzCardName()
	{
		return azCardName;
	}

	public String getFaction()
	{
		return faction;
	}

	public String getRarity()
	{
		return rarity;
	}

	public int getManaCost()
	{
		return manaCost;
	}

	/** @deprecated Use {@link #getManaCost()}. */
	@Deprecated
	public int getProvisions() { return manaCost; }

	public boolean isUnit()
	{
		return unit;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof CardInfo))
		{
			return false;
		}
		CardInfo that = (CardInfo) other;
		return manaCost == that.manaCost
			&& unit == that.unit
			&& id.equals(that.id)
			&& azCardName.equals(that.azCardName)
			&& faction.equals(that.faction)
			&& rarity.equals(that.rarity);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, azCardName, faction, rarity, manaCost, unit);
	}
}
