package com.osrstcgbattles.ui;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.collection.OwnedCardCollectionSnapshot;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckValidationResult;
import com.osrstcgbattles.deck.DeckValidator;
import com.osrstcgbattles.persist.DeckProfile;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import com.osrstcgbattles.party.PartyOpponent;
import java.util.List;

public interface BattleUiController
{
	BattleCardCatalog getCatalog();

	OwnedCardCollectionSnapshot getCollection();

	DeckProfile getDeckProfile();

	DeckValidationResult validate(Deck deck);

	DeckReadiness getDeckReadiness(Deck deck);

	List<Deck> getStarterDecks();

	boolean isStarterId(String deckId);

	default int copyLimit(BattleCard card)
	{
		String rarity = card.getRarity().name();
		return "LEGENDARY".equals(rarity) || "MYTHIC".equals(rarity)
			? DeckValidator.MAX_ELITE_COPIES : DeckValidator.MAX_STANDARD_COPIES;
	}

	void saveDeck(Deck deck, boolean select);

	void deleteDeck(String deckId);

	void selectDeck(String deckId);

	void refreshCollection();

	void openDeckBuilder();

	void startDemoMatch();

	PartyDuelSnapshot getPartyDuelSnapshot();

	List<PartyOpponent> getPartyOpponents();

	String getPartyDuelMessage();

	void invitePartyOpponent(long memberId);

	void acceptPartyDuel();

	void declinePartyDuel();

	void abortPartyDuel();

	default boolean isOnlinePlayEnabled() { return false; }

	default String getOnlineStatus() { return "Online play is disabled"; }
	default boolean canConnectOnline() { return false; }
	default boolean canStartOnlineWaiting() { return false; }
	default boolean canCancelOnlineWaiting() { return false; }

	default void connectOnline() { }

	default void createOnlineLobby() { }

	default void joinOnlineLobby(String code) { }

	default void joinCasualQueue() { }

	default void leaveCasualQueue() { }

	default void joinRankedQueue() { }

	default void leaveRankedQueue() { }
}
