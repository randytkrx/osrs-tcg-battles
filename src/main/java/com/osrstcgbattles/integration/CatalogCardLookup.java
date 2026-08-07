package com.osrstcgbattles.integration;

import com.osrstcgbattles.catalog.BattleCard;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.CardCategory;
import com.osrstcgbattles.deck.CardInfo;
import com.osrstcgbattles.deck.CardLookup;
import java.util.Objects;
import java.util.Optional;

public final class CatalogCardLookup implements CardLookup
{
	private final BattleCardCatalog catalog;

	public CatalogCardLookup(BattleCardCatalog catalog)
	{
		this.catalog = Objects.requireNonNull(catalog, "catalog");
	}

	@Override
	public Optional<CardInfo> findById(String cardId)
	{
		return catalog.findById(cardId).map(CatalogCardLookup::toCardInfo);
	}

	private static CardInfo toCardInfo(BattleCard card)
	{
		return new CardInfo(card.getId(), card.getAzCardName(), card.getFaction().name(),
			card.getRarity().name(), card.getManaCost(), card.getCategory() == CardCategory.UNIT);
	}
}
