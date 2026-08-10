package com.osrstcgbattles.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class BattleCardCatalog
{
	private static final BattleCard DEATHRATTLE_SPIRIT = new BattleCard("deathrattle-spirit", "Spirit", "Spirit",
		CardCategory.UNIT, Faction.NEUTRAL, Rarity.COMMON, 0, 1, 1, Collections.singleton("TOKEN"),
		"Summoned by a Deathrattle.", Collections.emptyList());
	private final int catalogVersion;
	private final int rulesetVersion;
	private final String sha256;
	private final List<BattleCard> cards;
	private final Map<String, BattleCard> cardsById;
	private final Map<String, BattleCard> cardsByAzName;

	BattleCardCatalog(int catalogVersion, int rulesetVersion, String sha256, List<BattleCard> cards)
	{
		this.catalogVersion = catalogVersion;
		this.rulesetVersion = rulesetVersion;
		this.sha256 = sha256;
		this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
		Map<String, BattleCard> byId = new LinkedHashMap<>();
		Map<String, BattleCard> byAzName = new LinkedHashMap<>();
		for (BattleCard card : cards)
		{
			byId.put(card.getId(), card);
			byAzName.put(card.getAzCardName(), card);
		}
		this.cardsById = Collections.unmodifiableMap(byId);
		this.cardsByAzName = Collections.unmodifiableMap(byAzName);
	}

	public int getCatalogVersion() { return catalogVersion; }
	public int getRulesetVersion() { return rulesetVersion; }
	public String getSha256() { return sha256; }
	public List<BattleCard> getCards() { return cards; }
	public Optional<BattleCard> findById(String id) { return Optional.ofNullable(cardsById.get(id)); }
	/** Includes non-collectible token definitions used only to render battlefield state. */
	public Optional<BattleCard> findBoardCardById(String id)
	{
		return DEATHRATTLE_SPIRIT.getId().equals(id) ? Optional.of(DEATHRATTLE_SPIRIT) : findById(id);
	}
	public Optional<BattleCard> findByAzCardName(String azCardName) { return Optional.ofNullable(cardsByAzName.get(azCardName)); }
}
