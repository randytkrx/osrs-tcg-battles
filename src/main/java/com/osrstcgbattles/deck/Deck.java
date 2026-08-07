package com.osrstcgbattles.deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Deck
{
	private final String id;
	private final String name;
	private final List<DeckEntry> entries;

	public Deck(String id, String name, List<DeckEntry> entries)
	{
		this.id = Objects.requireNonNull(id, "id");
		this.name = Objects.requireNonNull(name, "name");
		this.entries = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(entries, "entries")));
		if (this.entries.contains(null))
		{
			throw new NullPointerException("entries must not contain null");
		}
		Set<String> cardIds = new HashSet<>();
		for (DeckEntry entry : this.entries)
		{
			if (!cardIds.add(entry.getCardId()))
			{
				throw new IllegalArgumentException("Duplicate card id: " + entry.getCardId());
			}
		}
	}

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public List<DeckEntry> getEntries()
	{
		return entries;
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof Deck))
		{
			return false;
		}
		Deck that = (Deck) other;
		return id.equals(that.id) && name.equals(that.name) && entries.equals(that.entries);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, entries);
	}
}
