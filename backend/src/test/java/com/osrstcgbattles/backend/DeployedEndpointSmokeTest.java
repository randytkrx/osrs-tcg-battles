package com.osrstcgbattles.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.online.OnlineEnvelope;
import com.osrstcgbattles.online.OnlineMessageType;
import com.osrstcgbattles.online.RankedAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DeployedEndpointSmokeTest
{
	private static final String URL_ENVIRONMENT = "DUELSCAPE_SMOKE_URL";
	private static final long OPERATION_TIMEOUT_SECONDS = 10;

	@Test(timeout = 45_000L)
	public void deployedEndpointAcceptsHelloAndRankedAuthentication() throws Exception
	{
		String configuredUrl = System.getenv(URL_ENVIRONMENT);
		Assume.assumeTrue(URL_ENVIRONMENT + " is not set",
			configuredUrl != null && !configuredUrl.trim().isEmpty());
		String audience = configuredUrl.trim();
		URI endpoint = URI.create(audience);
		Gson gson = new Gson();
		BattleCardCatalog catalog = new BattleCardCatalogLoader(gson).loadDefault();
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		KeyPair keyPair = generator.generateKeyPair();
		String ign = "QA" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		byte[] publicKey = keyPair.getPublic().getEncoded();
		Messages listener = new Messages();
		WebSocket socket = null;

		try
		{
			socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(OPERATION_TIMEOUT_SECONDS))
				.build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(OPERATION_TIMEOUT_SECONDS))
				.buildAsync(endpoint, listener).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			JsonObject hello = new JsonObject();
			hello.addProperty("displayName", ign);
			hello.addProperty("catalogHash", catalog.getSha256());
			hello.addProperty("rulesetVersion", catalog.getRulesetVersion());
			send(socket, gson, OnlineMessageType.HELLO, hello);
			OnlineEnvelope welcome = listener.await(gson, OnlineMessageType.WELCOME);
			assertEquals(catalog.getSha256(), welcome.getPayload().get("catalogHash").getAsString());
			assertEquals(catalog.getRulesetVersion(), welcome.getPayload().get("rulesetVersion").getAsInt());

			JsonObject begin = new JsonObject();
			begin.addProperty("ign", ign);
			begin.addProperty("publicKey", encode(publicKey));
			send(socket, gson, OnlineMessageType.AUTH_BEGIN, begin);
			OnlineEnvelope challenge = listener.await(gson, OnlineMessageType.AUTH_CHALLENGE);
			String challengeId = challenge.getPayload().get("challengeId").getAsString();
			byte[] nonce = Base64.getUrlDecoder().decode(challenge.getPayload().get("nonce").getAsString());

			Signature signer = Signature.getInstance("SHA256withECDSA");
			signer.initSign(keyPair.getPrivate());
			signer.update(RankedAuthentication.challenge(audience, challengeId, nonce, ign, publicKey));
			JsonObject response = new JsonObject();
			response.addProperty("challengeId", challengeId);
			response.addProperty("signature", encode(signer.sign()));
			send(socket, gson, OnlineMessageType.AUTH_RESPONSE, response);
			OnlineEnvelope authenticated = listener.await(gson, OnlineMessageType.AUTHENTICATED);
			assertEquals(ign, authenticated.getPayload().get("ign").getAsString());
			assertFalse(authenticated.getPayload().get("accountId").getAsString().isEmpty());
		}
		finally
		{
			if (socket != null)
			{
				try
				{
					socket.sendClose(WebSocket.NORMAL_CLOSURE, "smoke test complete")
						.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
				catch (Exception ignored)
				{
					socket.abort();
				}
			}
		}
	}

	private static void send(WebSocket socket, Gson gson, OnlineMessageType type, JsonObject payload)
		throws Exception
	{
		OnlineEnvelope envelope = new OnlineEnvelope(UUID.randomUUID().toString(), type, payload);
		socket.sendText(gson.toJson(envelope), true).get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static String encode(byte[] value)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static final class Messages implements WebSocket.Listener
	{
		private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		private final StringBuilder partial = new StringBuilder();
		private volatile Throwable failure;

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

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
		{
			failure = new AssertionError("WebSocket closed before the smoke test completed (" + statusCode + ")");
			messages.offer("");
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error)
		{
			failure = error;
			messages.offer("");
		}

		private OnlineEnvelope await(Gson gson, OnlineMessageType expected) throws InterruptedException
		{
			String text = messages.poll(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (failure != null) throw new AssertionError("WebSocket failed before " + expected, failure);
			if (text == null) throw new AssertionError("Timed out waiting for " + expected);
			OnlineEnvelope envelope;
			try
			{
				envelope = gson.fromJson(text, OnlineEnvelope.class);
				if (envelope == null) throw new JsonParseException("null envelope");
				envelope.validate();
			}
			catch (RuntimeException exception)
			{
				throw new AssertionError("Received an invalid protocol response");
			}
			if (envelope.getType() == OnlineMessageType.ERROR)
			{
				JsonObject payload = envelope.getPayload();
				String code = payload.has("code") ? payload.get("code").getAsString() : "unknown";
				String message = payload.has("message") ? payload.get("message").getAsString() : "no message";
				throw new AssertionError("Expected " + expected + " but server returned " + code + ": " + message);
			}
			assertEquals(expected, envelope.getType());
			return envelope;
		}
	}
}
