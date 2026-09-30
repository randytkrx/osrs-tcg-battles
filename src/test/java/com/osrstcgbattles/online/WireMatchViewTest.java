package com.osrstcgbattles.online;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.match.MatchView;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WireMatchViewTest
{
	@Test
	public void jsonExcludesOpponentHiddenCards()
	{
		MatchState state = new DuelscapeEngine().newMatch(deck("visible-local"), deck("hidden-opponent"), 41L);
		String json = new Gson().toJson(WireMatchView.from(MatchView.forPlayer(state, PlayerId.PLAYER_ONE)));

		assertTrue(json.contains("visible-local"));
		assertFalse(json.contains("hidden-opponent"));
		assertTrue(json.contains("\"handSize\":"));
		assertTrue(json.contains("\"drawPileSize\":"));
	}

	@Test
	public void jsonRoundTripRebuildsRenderableView()
	{
		Gson gson = new Gson();
		BattleCardCatalog catalog = new BattleCardCatalogLoader(gson).loadDefault();
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog);
		MatchState state = new DuelscapeEngine().newMatchWithMulligan(
			factory.create(factory.randomDeck()), factory.create(factory.randomDeck()), 73L);
		MatchView original = MatchView.forPlayer(state, PlayerId.PLAYER_TWO);

		WireMatchView wire = gson.fromJson(gson.toJson(WireMatchView.from(original)), WireMatchView.class);
		MatchView rebuilt = wire.toMatchView(catalog);

		assertEquals(original.getViewer(), rebuilt.getViewer());
		assertEquals(original.getPublicStateHash(), rebuilt.getPublicStateHash());
		assertEquals(original.getLocalHand().size(), rebuilt.getLocalHand().size());
		assertEquals(original.getPlayer(PlayerId.PLAYER_ONE).getDrawPileSize(),
			rebuilt.getPlayer(PlayerId.PLAYER_ONE).getDrawPileSize());
		assertEquals(original.getPlayer(PlayerId.PLAYER_ONE).isMulliganFinished(),
			rebuilt.getPlayer(PlayerId.PLAYER_ONE).isMulliganFinished());
		assertEquals(original.getPlayer(PlayerId.PLAYER_TWO).isMulliganFinished(),
			rebuilt.getPlayer(PlayerId.PLAYER_TWO).isMulliganFinished());
	}

	@Test
	public void missingPrimitiveFieldsAreRejected()
	{
		JsonObject topLevel = validJson();
		topLevel.remove("turnNumber");
		assertRejected(topLevel);

		String[] playerFields = {"heroHealth", "mana", "temporaryMana", "maximumMana", "fatigue",
			"turnsStarted", "handSize", "drawPileSize", "remainingMulligans", "mulliganFinished"};
		for (String field : playerFields)
		{
			JsonObject json = validJson();
			json.getAsJsonObject("players").getAsJsonObject("PLAYER_ONE").remove(field);
			assertRejected(json);
		}

		String[] unitFields = {"attack", "health", "ready", "shielded", "stealthed", "rushRestricted"};
		for (String field : unitFields)
		{
			JsonObject json = validJsonWithUnit();
			firstUnit(json, "PLAYER_ONE").remove(field);
			assertRejected(json);
		}
	}

	@Test
	public void invalidBoundsAreRejected()
	{
		JsonObject turn = validJson();
		turn.addProperty("turnNumber", 0);
		assertRejected(turn);

		JsonObject health = validJson();
		health.getAsJsonObject("players").getAsJsonObject("PLAYER_ONE").addProperty("heroHealth", 21);
		assertRejected(health);

		JsonObject mana = validJson();
		JsonObject player = mana.getAsJsonObject("players").getAsJsonObject("PLAYER_ONE");
		player.addProperty("maximumMana", 0);
		player.addProperty("mana", 1);
		assertRejected(mana);

		JsonObject hand = validJson();
		hand.getAsJsonObject("players").getAsJsonObject("PLAYER_TWO").addProperty("handSize", 11);
		assertRejected(hand);

		JsonObject drawPile = validJson();
		drawPile.getAsJsonObject("players").getAsJsonObject("PLAYER_TWO").addProperty("drawPileSize", 31);
		assertRejected(drawPile);

		JsonObject mulligans = validJson();
		mulligans.getAsJsonObject("players").getAsJsonObject("PLAYER_TWO").addProperty("remainingMulligans", 6);
		assertRejected(mulligans);

		JsonObject unit = validJsonWithUnit();
		firstUnit(unit, "PLAYER_ONE").addProperty("attack", -1);
		assertRejected(unit);

		JsonObject oversizedBoard = validJsonWithUnit();
		JsonArray units = oversizedBoard.getAsJsonObject("board").getAsJsonArray("PLAYER_ONE");
		for (int i = 1; i < 8; i++)
		{
			JsonObject copy = copy(units.get(0).getAsJsonObject());
			copy.addProperty("instanceId", "unit-" + i);
			units.add(copy);
		}
		assertRejected(oversizedBoard);
	}

	@Test
	public void missingRequiredStructuresAndStringsAreRejected()
	{
		String[] topLevelFields = {"viewer", "startingPlayer", "status", "phase", "publicStateHash",
			"players", "localHand", "board"};
		for (String field : topLevelFields)
		{
			JsonObject json = validJson();
			json.remove(field);
			assertRejected(json);
		}

		JsonObject player = validJson();
		player.getAsJsonObject("players").remove("PLAYER_TWO");
		assertRejected(player);

		JsonObject graveyard = validJson();
		graveyard.getAsJsonObject("players").getAsJsonObject("PLAYER_ONE").remove("graveyard");
		assertRejected(graveyard);

		JsonObject board = validJson();
		board.getAsJsonObject("board").remove("PLAYER_TWO");
		assertRejected(board);

		JsonObject instanceId = validJsonWithUnit();
		firstUnit(instanceId, "PLAYER_ONE").addProperty("instanceId", "");
		assertRejected(instanceId);

		JsonObject cardId = validJsonWithUnit();
		firstUnit(cardId, "PLAYER_ONE").remove("cardId");
		assertRejected(cardId);
	}

	@Test
	public void duplicateBoardInstanceIdsAreRejectedAcrossPlayers()
	{
		JsonObject json = validJsonWithUnit();
		json.getAsJsonObject("board").getAsJsonArray("PLAYER_TWO")
			.add(copy(firstUnit(json, "PLAYER_ONE")));

		assertRejected(json);
	}

	@Test
	public void viewerHandMustMatchPublishedHandSize()
	{
		JsonObject json = validJson();
		String viewer = json.get("viewer").getAsString();
		json.getAsJsonObject("players").getAsJsonObject(viewer).addProperty("handSize", 0);

		assertRejected(json);
	}

	@Test
	public void validBoardFlagsSurviveRoundTrip()
	{
		JsonObject json = validJsonWithUnit();
		JsonObject unit = firstUnit(json, "PLAYER_ONE");
		unit.addProperty("ready", true);
		unit.addProperty("shielded", true);
		unit.addProperty("stealthed", true);
		unit.addProperty("rushRestricted", true);

		MatchView rebuilt = new Gson().fromJson(json, WireMatchView.class).toMatchView(catalog());

		assertTrue(rebuilt.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).isReady());
		assertTrue(rebuilt.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).isShielded());
		assertTrue(rebuilt.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).isStealthed());
		assertTrue(rebuilt.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).isRushRestricted());
	}

	private static JsonObject validJson()
	{
		Gson gson = new Gson();
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog());
		MatchState state = new DuelscapeEngine().newMatchWithMulligan(
			factory.create(factory.randomDeck()), factory.create(factory.randomDeck()), 73L);
		return gson.fromJson(gson.toJson(WireMatchView.from(MatchView.forPlayer(state, PlayerId.PLAYER_TWO))),
			JsonObject.class);
	}

	private static JsonObject validJsonWithUnit()
	{
		JsonObject json = validJson();
		JsonObject unit = new JsonObject();
		unit.addProperty("instanceId", "unit-0");
		unit.addProperty("cardId", "deathrattle-spirit");
		unit.addProperty("attack", 1);
		unit.addProperty("health", 1);
		unit.addProperty("ready", false);
		unit.addProperty("shielded", false);
		unit.addProperty("stealthed", false);
		unit.addProperty("rushRestricted", false);
		json.getAsJsonObject("board").getAsJsonArray("PLAYER_ONE").add(unit);
		JsonObject player = json.getAsJsonObject("players").getAsJsonObject("PLAYER_ONE");
		player.addProperty("drawPileSize", player.get("drawPileSize").getAsInt() - 1);
		return json;
	}

	private static JsonObject firstUnit(JsonObject json, String player)
	{
		return json.getAsJsonObject("board").getAsJsonArray(player).get(0).getAsJsonObject();
	}

	private static JsonObject copy(JsonObject json)
	{
		return new Gson().fromJson(json.toString(), JsonObject.class);
	}

	private static BattleCardCatalog catalog()
	{
		return new BattleCardCatalogLoader(new Gson()).loadDefault();
	}

	private static void assertRejected(JsonObject json)
	{
		WireMatchView wire = new Gson().fromJson(json, WireMatchView.class);
		assertThrows(IllegalArgumentException.class, () -> wire.toMatchView(catalog()));
	}

	private static List<Card> deck(String id)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++) cards.add(new UnitCard(id, id, 0, 1, 1));
		return cards;
	}
}
