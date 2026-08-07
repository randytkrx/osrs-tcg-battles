package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidationError;
import com.osrstcgbattles.deck.DeckValidationResult;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import java.util.Map;

final class BattleUiFormatters
{
	private BattleUiFormatters()
	{
	}

	static String player(PlayerId player)
	{
		return player == PlayerId.PLAYER_ONE ? "Player 1" : "Player 2";
	}

	static String collection(OwnedCardCollectionSnapshot collection)
	{
		return collection.isKnown() ? collection.getOwnedNames().size() + " owned cards" : "Ownership not loaded";
	}

	static String validation(DeckValidationResult result)
	{
		return validation(result, true);
	}

	static String validation(DeckValidationResult result, boolean ownershipKnown)
	{
		int count = (int) result.getErrors().stream()
			.filter(error -> ownershipKnown || error.getCode() != DeckValidationError.Code.UNOWNED_CARD)
			.count();
		if (!ownershipKnown && count == 0 && !result.isValid())
		{
			return "Ownership validation pending";
		}
		return result.isValid() ? "Valid deck" : count + (count == 1 ? " deck error" : " deck errors");
	}

	static String partyDuelStatus(PartyDuelSnapshot snapshot)
	{
		if (snapshot.getStatus() == PartyDuelSnapshot.Status.READY)
		{
			return "Secure friend duel active";
		}
		String peer = snapshot.getPeerDisplayName();
		return peer == null || peer.isEmpty() ? snapshot.getUserStatus()
			: snapshot.getUserStatus() + " (" + peer + ")";
	}

	static boolean canInvitePartyDuel(PartyDuelSnapshot snapshot, boolean hasOpponent, boolean deckAvailable)
	{
		return (snapshot.getStatus() == PartyDuelSnapshot.Status.IDLE
			|| snapshot.getStatus() == PartyDuelSnapshot.Status.TERMINAL) && hasOpponent && deckAvailable;
	}

	static boolean canAcceptPartyDuel(PartyDuelSnapshot snapshot, boolean deckAvailable)
	{
		return snapshot.canAccept() && deckAvailable;
	}

	static int cardCount(Map<String, Integer> quantities)
	{
		return quantities.values().stream().mapToInt(Integer::intValue).sum();
	}

	static int totalManaCost(BattleCardCatalog catalog, Map<String, Integer> quantities)
	{
		int total = 0;
		for (Map.Entry<String, Integer> entry : quantities.entrySet())
		{
			BattleCard card = catalog.findById(entry.getKey()).orElse(null);
			if (card != null)
			{
				total += card.getManaCost() * entry.getValue();
			}
		}
		return total;
	}

	static boolean ownershipMatches(int filter, boolean ownershipKnown, boolean owned)
	{
		return !ownershipKnown || filter == 0 || (filter == 1 && owned) || (filter == 2 && !owned);
	}

	static String ownershipMarker(boolean ownershipKnown, boolean owned)
	{
		return ownershipKnown ? (owned ? "OWNED" : "UNOWNED") : "UNKNOWN";
	}

	static String deckChoice(Deck deck, String selectedId)
	{
		return deck.getName() + (deck.getId().equals(selectedId) ? " (selected)" : "");
	}

	static java.util.List<DeckEntry> entries(Map<String, Integer> quantities)
	{
		java.util.List<DeckEntry> entries = new java.util.ArrayList<>();
		for (Map.Entry<String, Integer> entry : quantities.entrySet())
		{
			if (entry.getValue() > 0)
			{
				entries.add(new DeckEntry(entry.getKey(), entry.getValue()));
			}
		}
		return entries;
	}

	static Map<String, Integer> quantities(Deck deck)
	{
		Map<String, Integer> quantities = new java.util.LinkedHashMap<>();
		for (DeckEntry entry : deck.getEntries())
		{
			quantities.merge(entry.getCardId(), entry.getCount(), Math::addExact);
		}
		return quantities;
	}
}
