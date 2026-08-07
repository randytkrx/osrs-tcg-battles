package com.osrstcgbattles.deck;

import java.util.Objects;

public final class DeckEntry
{
	private final String cardId;
	private final int count;

	public DeckEntry(String cardId, int count)
	{
		this.cardId = Objects.requireNonNull(cardId, "cardId");
		this.count = count;
	}

	public String getCardId()
	{
		return cardId;
	}

	public int getCount()
	{
		return count;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof DeckEntry))
		{
			return false;
		}
		DeckEntry that = (DeckEntry) other;
		return count == that.count && cardId.equals(that.cardId);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(cardId, count);
	}
}
