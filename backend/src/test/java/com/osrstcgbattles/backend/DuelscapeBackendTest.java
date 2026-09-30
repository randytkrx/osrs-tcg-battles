package com.osrstcgbattles.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.online.OnlineEnvelope;
import com.osrstcgbattles.online.OnlineMessageType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DuelscapeBackendTest
{
	@Test
	public void healthReportsProtocolAndRuleset() throws Exception
	{
		try (DuelscapeBackend backend = DuelscapeBackend.create("127.0.0.1", 0))
		{
			backend.start();
			HttpResponse<String> response = send(backend, "GET");
			assertEquals(200, response.statusCode());
			assertEquals("application/json; charset=utf-8",
				response.headers().firstValue("Content-Type").orElse(null));
			assertTrue(response.body().contains("\"protocolVersion\":" + DuelscapeBackend.PROTOCOL_VERSION));
			assertTrue(response.body().contains("\"rulesetVersion\":" + DuelscapeEngine.RULESET_VERSION));
		}
	}

	@Test
	public void healthRejectsMutationMethods() throws Exception
	{
		try (DuelscapeBackend backend = DuelscapeBackend.create("127.0.0.1", 0))
		{
			backend.start();
			HttpResponse<String> response = send(backend, "POST");
			assertEquals(405, response.statusCode());
			assertEquals("GET", response.headers().firstValue("Allow").orElse(null));
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidPort()
	{
		DuelscapeBackend.parsePort("70000");
	}

	@Test
	public void websocketAcceptsVersionedHello() throws Exception
	{
		try (DuelscapeBackend backend = DuelscapeBackend.create("127.0.0.1", 0))
		{
			backend.start();
			Gson gson = new Gson();
			Messages listener = new Messages();
			URI uri = URI.create("ws://127.0.0.1:" + backend.getAddress().getPort() + "/v1/ws");
			WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
				.connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, listener).join();
			JsonObject payload = new JsonObject();
			payload.addProperty("displayName", "Test Player");
			payload.addProperty("catalogHash", new BattleCardCatalogLoader(gson).loadDefault().getSha256());
			payload.addProperty("rulesetVersion", DuelscapeEngine.RULESET_VERSION);
			socket.sendText(gson.toJson(new OnlineEnvelope("hello", OnlineMessageType.HELLO, payload)), true).join();

			String text = listener.messages.poll(5, TimeUnit.SECONDS);
			OnlineEnvelope response = gson.fromJson(text, OnlineEnvelope.class);
			assertEquals(OnlineMessageType.WELCOME, response.getType());
			assertTrue(response.getPayload().has("reconnectToken"));
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
		}
	}

	private static HttpResponse<String> send(DuelscapeBackend backend, String method) throws Exception
	{
		URI uri = URI.create("http://127.0.0.1:" + backend.getAddress().getPort() + "/healthz");
		HttpRequest request = HttpRequest.newBuilder(uri).method(method,
			HttpRequest.BodyPublishers.noBody()).build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static final class Messages implements WebSocket.Listener
	{
		private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		private final StringBuilder partial = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket)
		{
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
		{
			partial.append(data);
			if (last)
			{
				messages.add(partial.toString());
				partial.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message)
		{
			webSocket.request(1);
			return webSocket.sendPong(message);
		}
	}
}
