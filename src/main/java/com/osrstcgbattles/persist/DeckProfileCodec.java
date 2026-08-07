package com.osrstcgbattles.persist;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Versioned JSON codec containing only non-sensitive deck data. */
public final class DeckProfileCodec
{
	public static final int CURRENT_VERSION = 1;
	public static final int MAX_DECKS = 100;
	public static final int MAX_ENTRIES_PER_DECK = 100;
	public static final int MAX_JSON_BYTES = 1024 * 1024;

	private final Gson gson;

	public DeckProfileCodec(Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public String encode(DeckProfile profile)
	{
		Objects.requireNonNull(profile, "profile");
		if (profile.getDecks().size() > MAX_DECKS)
		{
			throw new IllegalArgumentException("Profile contains too many decks");
		}
		StoredProfile stored = new StoredProfile();
		stored.version = CURRENT_VERSION;
		stored.selectedDeckId = profile.getSelectedDeckId().orElse(null);
		stored.decks = new ArrayList<>();
		for (Deck deck : profile.getDecks())
		{
			validateDeck(deck);
			StoredDeck storedDeck = new StoredDeck();
			storedDeck.id = deck.getId();
			storedDeck.name = deck.getName();
			storedDeck.entries = new ArrayList<>();
			for (DeckEntry entry : deck.getEntries())
			{
				StoredEntry storedEntry = new StoredEntry();
				storedEntry.cardId = entry.getCardId();
				storedEntry.count = entry.getCount();
				storedDeck.entries.add(storedEntry);
			}
			stored.decks.add(storedDeck);
		}
		String json = gson.toJson(stored);
		if (utf8Length(json) > MAX_JSON_BYTES)
		{
			throw new IllegalArgumentException("Encoded profile is too large");
		}
		return json;
	}

	public Optional<DeckProfile> decode(String json)
	{
		if (json == null || json.trim().isEmpty())
		{
			return Optional.empty();
		}
		if (utf8Length(json) > MAX_JSON_BYTES)
		{
			return Optional.empty();
		}
		try
		{
			StoredProfile stored = gson.fromJson(json, StoredProfile.class);
			if (stored == null || stored.version != CURRENT_VERSION || stored.decks == null
				|| stored.decks.size() > MAX_DECKS)
			{
				return Optional.empty();
			}
			List<Deck> decks = new ArrayList<>();
			for (StoredDeck storedDeck : stored.decks)
			{
				if (storedDeck == null || isBlank(storedDeck.id) || isBlank(storedDeck.name)
					|| storedDeck.entries == null || storedDeck.entries.size() > MAX_ENTRIES_PER_DECK)
				{
					return Optional.empty();
				}
				List<DeckEntry> entries = new ArrayList<>();
				Set<String> cardIds = new HashSet<>();
				for (StoredEntry storedEntry : storedDeck.entries)
				{
					if (storedEntry == null || isBlank(storedEntry.cardId)
						|| storedEntry.count <= 0 || storedEntry.count > DeckValidator.MAX_CARDS
						|| !cardIds.add(storedEntry.cardId))
					{
						return Optional.empty();
					}
					entries.add(new DeckEntry(storedEntry.cardId, storedEntry.count));
				}
				decks.add(new Deck(storedDeck.id, storedDeck.name, entries));
			}
			return Optional.of(new DeckProfile(decks, stored.selectedDeckId));
		}
		catch (JsonParseException | IllegalArgumentException | NullPointerException exception)
		{
			return Optional.empty();
		}
	}

	private static void validateDeck(Deck deck)
	{
		if (isBlank(deck.getId()) || isBlank(deck.getName()))
		{
			throw new IllegalArgumentException("Deck id and name must not be blank");
		}
		if (deck.getEntries().size() > MAX_ENTRIES_PER_DECK)
		{
			throw new IllegalArgumentException("Deck contains too many entries");
		}
		for (DeckEntry entry : deck.getEntries())
		{
			if (isBlank(entry.getCardId()) || entry.getCount() <= 0 || entry.getCount() > DeckValidator.MAX_CARDS)
			{
				throw new IllegalArgumentException("Deck contains an invalid entry");
			}
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.isBlank();
	}

	private static int utf8Length(String value)
	{
		if (value.length() > MAX_JSON_BYTES)
		{
			return value.length();
		}
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static final class StoredProfile
	{
		private int version;
		private List<StoredDeck> decks;
		private String selectedDeckId;
	}

	private static final class StoredDeck
	{
		private String id;
		private String name;
		private List<StoredEntry> entries;
	}

	private static final class StoredEntry
	{
		private String cardId;
		private int count;
	}
}
