package com.osrstcgbattles.persist;

import com.osrstcgbattles.deck.Deck;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable collection of decks and the deck selected in the active RuneLite profile. */
public final class DeckProfile
{
	private final List<Deck> decks;
	private final String selectedDeckId;

	public DeckProfile(List<Deck> decks, String selectedDeckId)
	{
		this.decks = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(decks, "decks")));
		if (this.decks.contains(null))
		{
			throw new NullPointerException("decks must not contain null");
		}

		Set<String> ids = new HashSet<>();
		for (Deck deck : this.decks)
		{
			if (!ids.add(deck.getId()))
			{
				throw new IllegalArgumentException("Duplicate deck id: " + deck.getId());
			}
		}
		if (selectedDeckId != null && !ids.contains(selectedDeckId))
		{
			throw new IllegalArgumentException("Selected deck does not exist: " + selectedDeckId);
		}
		this.selectedDeckId = selectedDeckId;
	}

	public static DeckProfile empty()
	{
		return new DeckProfile(Collections.emptyList(), null);
	}

	public List<Deck> getDecks()
	{
		return decks;
	}

	public Optional<String> getSelectedDeckId()
	{
		return Optional.ofNullable(selectedDeckId);
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof DeckProfile))
		{
			return false;
		}
		DeckProfile that = (DeckProfile) other;
		return decks.equals(that.decks) && Objects.equals(selectedDeckId, that.selectedDeckId);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(decks, selectedDeckId);
	}
}
