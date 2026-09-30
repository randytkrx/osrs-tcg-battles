package com.osrstcgbattles.online;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.match.MatchView;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class OnlineMatchCoordinatorTest
{
	private final Gson gson = new Gson();
	private final BattleCardCatalog catalog = new BattleCardCatalogLoader(gson).loadDefault();

	@Test
	public void equalRevisionDuplicateIsIgnoredAndAbortIsIrreversible()
	{
		OnlineMatchCoordinator coordinator = coordinator();
		OnlineEnvelope state = stateMessage(OnlineMessageType.MATCH_STATE, 0, activeView());

		assertEquals(OnlineMatchCoordinator.ReceiveResult.UPDATED, coordinator.receive(state));
		assertEquals(OnlineMatchCoordinator.ReceiveResult.IGNORED, coordinator.receive(state));

		JsonObject aborted = new JsonObject();
		aborted.addProperty("matchId", "match-1");
		aborted.addProperty("reason", "server restart");
		assertEquals(OnlineMatchCoordinator.ReceiveResult.TERMINAL_ACCEPTED,
			coordinator.receive(new OnlineEnvelope("abort", OnlineMessageType.MATCH_ABORTED, aborted)));
		assertEquals(OnlineMatchCoordinator.ReceiveResult.IGNORED,
			coordinator.receive(stateMessage(OnlineMessageType.MATCH_STATE, 1, activeView())));
	}

	@Test
	public void completeEventMustContainCompleteView()
	{
		assertThrows(IllegalArgumentException.class,
			() -> coordinator().receive(stateMessage(OnlineMessageType.MATCH_COMPLETE, 1, activeView())));
	}

	@Test
	public void revisionMustBeAnExactNonnegativeInteger()
	{
		JsonObject payload = stateMessage(OnlineMessageType.MATCH_STATE, 0, activeView()).getPayload();
		payload.addProperty("revision", 0.5);
		assertThrows(IllegalArgumentException.class,
			() -> coordinator().receive(new OnlineEnvelope("state", OnlineMessageType.MATCH_STATE, payload)));
	}

	private OnlineMatchCoordinator coordinator()
	{
		JsonObject found = new JsonObject();
		found.addProperty("matchId", "match-1");
		found.addProperty("seat", "PLAYER_ONE");
		found.addProperty("opponentDisplayName", "Opponent");
		found.addProperty("revision", 0);
		return new OnlineMatchCoordinator(new OnlineBattleClient(new OkHttpClient(), gson), gson, catalog,
			new OnlineEnvelope("found", OnlineMessageType.MATCH_FOUND, found));
	}

	private OnlineEnvelope stateMessage(OnlineMessageType type, long revision, WireMatchView view)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", "match-1");
		payload.addProperty("revision", revision);
		payload.add("view", gson.toJsonTree(view));
		return new OnlineEnvelope("state", type, payload);
	}

	private WireMatchView activeView()
	{
		CatalogDeckFactory factory = new CatalogDeckFactory(catalog);
		MatchState state = new DuelscapeEngine().newMatchWithMulligan(
			factory.create(factory.randomDeck()), factory.create(factory.randomDeck()), 19L);
		return WireMatchView.from(MatchView.forPlayer(state, PlayerId.PLAYER_ONE));
	}
}
