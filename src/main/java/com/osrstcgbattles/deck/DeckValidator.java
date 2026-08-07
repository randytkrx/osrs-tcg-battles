package com.osrstcgbattles.deck;

import com.osrstcgbattles.collection.OwnedCardCollectionStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DeckValidator
{
	public static final int MIN_CARDS = 30;
	public static final int MAX_CARDS = 30;
	public static final int MAX_STANDARD_COPIES = 2;
	public static final int MAX_ELITE_COPIES = 1;

	public DeckValidationResult validate(Deck deck, CardLookup cards, Set<String> ownedAzCardNames)
	{
		return validate(deck, cards, ownedAzCardNames, OwnedCardCollectionStatus.KNOWN);
	}

	public DeckValidationResult validate(Deck deck, CardLookup cards, Set<String> ownedAzCardNames,
		boolean ownershipKnown)
	{
		return validate(deck, cards, ownedAzCardNames,
			ownershipKnown ? OwnedCardCollectionStatus.KNOWN : OwnedCardCollectionStatus.UNKNOWN);
	}

	/**
	 * Validates a deck, optionally skipping ownership errors when ownership data is not yet known.
	 */
	public DeckValidationResult validate(Deck deck, CardLookup cards, Set<String> ownedAzCardNames,
		OwnedCardCollectionStatus ownershipStatus)
	{
		Objects.requireNonNull(deck, "deck");
		Objects.requireNonNull(cards, "cards");
		Objects.requireNonNull(ownedAzCardNames, "ownedAzCardNames");
		Objects.requireNonNull(ownershipStatus, "ownershipStatus");

		List<DeckValidationError> errors = new ArrayList<>();
		Set<String> ownedNames = new HashSet<>();
		for (String name : ownedAzCardNames)
		{
			if (name != null)
			{
				ownedNames.add(normalize(name));
			}
		}

		long totalCards = 0;
		for (DeckEntry entry : deck.getEntries())
		{
			int count = entry.getCount();
			if (count <= 0 || count > MAX_CARDS)
			{
				errors.add(error(DeckValidationError.Code.INVALID_ENTRY_COUNT,
					"Card count must be between 1 and " + MAX_CARDS + " for " + entry.getCardId(),
					entry.getCardId()));
				continue;
			}

			totalCards = boundedAdd(totalCards, count, MAX_CARDS + 1L);
			Optional<CardInfo> resolved = Objects.requireNonNull(cards.findById(entry.getCardId()),
				"CardLookup must return Optional.empty() for an unknown card");
			if (!resolved.isPresent())
			{
				errors.add(error(DeckValidationError.Code.UNKNOWN_CARD,
					"Unknown card: " + entry.getCardId(), entry.getCardId()));
				continue;
			}

			String cardId = entry.getCardId();
			CardInfo card = resolved.get();
			int copyLimit = copyLimit(card);
			if (count > copyLimit)
			{
				DeckValidationError.Code code = copyLimit == MAX_ELITE_COPIES
					? DeckValidationError.Code.ELITE_COPY_LIMIT : DeckValidationError.Code.COPY_LIMIT;
				errors.add(error(code, card.getAzCardName() + " allows at most " + copyLimit + " copies", cardId));
			}
			if (ownershipStatus == OwnedCardCollectionStatus.KNOWN
				&& !ownedNames.contains(normalize(card.getAzCardName())))
			{
				errors.add(error(DeckValidationError.Code.UNOWNED_CARD,
					"Card is not owned: " + card.getAzCardName(), cardId));
			}
		}

		if (totalCards < MIN_CARDS || totalCards > MAX_CARDS)
		{
			errors.add(error(DeckValidationError.Code.CARD_COUNT,
				"Deck must contain exactly 30 cards; found " + totalCards, null));
		}
		return new DeckValidationResult(errors);
	}

	public static int copyLimit(CardInfo card)
	{
		Objects.requireNonNull(card, "card");
		String rarity = card.getRarity();
		return "LEGENDARY".equalsIgnoreCase(rarity) || "MYTHIC".equalsIgnoreCase(rarity)
			? MAX_ELITE_COPIES : MAX_STANDARD_COPIES;
	}

	private static long boundedAdd(long total, long value, long upperBound)
	{
		if (value > 0 && total >= upperBound - value)
		{
			return upperBound;
		}
		if (value < 0 && total < Long.MIN_VALUE - value)
		{
			return Long.MIN_VALUE;
		}
		return total + value;
	}

	private static String normalize(String value)
	{
		return value.toLowerCase(Locale.ROOT);
	}

	private static DeckValidationError error(DeckValidationError.Code code, String message, String cardId)
	{
		return new DeckValidationError(code, message, cardId);
	}
}
