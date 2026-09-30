package com.osrstcgbattles.online;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.osrstcgbattles.auth.DeviceKeyStore;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.engine.MulliganCommand;
import com.osrstcgbattles.engine.PlayCardCommand;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Thread-safe RuneLite adapter for the versioned Duelscape WebSocket protocol. */
@Singleton
public final class OnlineBattleClient
{
	private static final int MAX_MESSAGE_BYTES = 64 * 1024;
	private final OkHttpClient httpClient;
	private final Gson gson;
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "duelscape-reconnect");
		thread.setDaemon(true);
		return thread;
	});
	private WebSocket socket;
	private long generation;
	private String displayName;
	private BattleCardCatalog catalog;
	private DeviceKeyStore deviceKey;
	private String reconnectToken;
	private String reconnectServerUrl;
	private boolean rankedAuthenticated;
	private Deck pendingRankedDeck;
	private WaitingState waitingState = WaitingState.NONE;
	private Status status = Status.DISCONNECTED;

	@Inject
	public OnlineBattleClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public synchronized void start(String serverUrl, String displayName, BattleCardCatalog catalog,
		DeviceKeyStore deviceKey)
	{
		if (serverUrl == null || !serverUrl.startsWith("wss://"))
			throw new IllegalArgumentException("online server must use wss://");
		stopSocket();
		if (!serverUrl.equals(reconnectServerUrl)) reconnectToken = null;
		reconnectServerUrl = serverUrl;
		this.displayName = required(displayName, 32, "displayName");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.deviceKey = Objects.requireNonNull(deviceKey, "deviceKey");
		rankedAuthenticated = false;
		pendingRankedDeck = null;
		waitingState = WaitingState.NONE;
		long activeGeneration = ++generation;
		status = Status.CONNECTING;
		publishStatus(generation, null);
		Request request = new Request.Builder().url(serverUrl).build();
		socket = httpClient.newWebSocket(request, new SocketListener(activeGeneration, 0));
	}

	public synchronized void stop()
	{
		generation++;
		stopSocket();
		reconnectToken = null;
		reconnectServerUrl = null;
		rankedAuthenticated = false;
		pendingRankedDeck = null;
		waitingState = WaitingState.NONE;
		status = Status.DISCONNECTED;
		publishStatus(generation, null);
	}

	public synchronized boolean createLobby(Deck deck)
	{
		JsonObject payload = new JsonObject();
		payload.add("deck", deckPayload(deck));
		return send(OnlineMessageType.LOBBY_CREATE, payload);
	}

	public synchronized boolean joinLobby(String code, Deck deck)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("code", required(code, 6, "code"));
		payload.add("deck", deckPayload(deck));
		return send(OnlineMessageType.LOBBY_JOIN, payload);
	}

	public synchronized boolean leaveLobby()
	{
		return send(OnlineMessageType.LOBBY_LEAVE, new JsonObject());
	}

	public synchronized boolean joinCasualQueue(Deck deck)
	{
		JsonObject payload = new JsonObject();
		payload.add("deck", deckPayload(deck));
		return send(OnlineMessageType.QUEUE_JOIN, payload);
	}

	public synchronized boolean leaveQueue()
	{
		if (pendingRankedDeck != null)
		{
			pendingRankedDeck = null;
			if (status == Status.AUTHENTICATING) status = Status.READY;
			publishStatus(generation, null);
			return true;
		}
		if (waitingState == WaitingState.LOBBY) return leaveLobby();
		if (waitingState == WaitingState.RANKED_QUEUE) return leaveRankedQueue();
		return send(OnlineMessageType.QUEUE_LEAVE, new JsonObject());
	}

	public synchronized boolean joinRankedQueue(Deck deck)
	{
		if (status != Status.READY || socket == null) return false;
		if (!rankedAuthenticated)
		{
			if (pendingRankedDeck != null) return false;
			pendingRankedDeck = Objects.requireNonNull(deck, "deck");
			status = Status.AUTHENTICATING;
			if (!sendAuthenticationBegin(socket))
			{
				pendingRankedDeck = null;
				status = Status.READY;
			}
			publishStatus(generation, null);
			return pendingRankedDeck != null;
		}
		JsonObject payload = new JsonObject();
		payload.add("deck", deckPayload(deck));
		return send(OnlineMessageType.RANKED_QUEUE_JOIN, payload);
	}

	public synchronized boolean leaveRankedQueue()
	{
		if (pendingRankedDeck != null)
		{
			pendingRankedDeck = null;
			if (status == Status.AUTHENTICATING) status = Status.READY;
			publishStatus(generation, null);
			return true;
		}
		return send(OnlineMessageType.RANKED_QUEUE_LEAVE, new JsonObject());
	}

	public synchronized boolean acknowledgeMatch(String matchId, boolean terminal)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", required(matchId, 64, "matchId"));
		payload.addProperty("terminal", terminal);
		return send(OnlineMessageType.MATCH_ACK, payload);
	}

	public synchronized boolean abandonMatch(String matchId)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", required(matchId, 64, "matchId"));
		return send(OnlineMessageType.MATCH_ABANDON, payload);
	}

	public synchronized boolean sendCommand(String matchId, long revision, Command command)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("matchId", required(matchId, 64, "matchId"));
		payload.addProperty("commandId", UUID.randomUUID().toString());
		payload.addProperty("expectedRevision", revision);
		payload.add("command", commandPayload(command));
		return send(OnlineMessageType.MATCH_COMMAND, payload);
	}

	public Status getStatus()
	{
		synchronized (this) { return status; }
	}

	public synchronized long getGeneration() { return generation; }
	public synchronized boolean canStartWaiting()
	{
		return status == Status.READY && waitingState == WaitingState.NONE && pendingRankedDeck == null;
	}
	public synchronized boolean canCancelWaiting()
	{
		return pendingRankedDeck != null || waitingState != WaitingState.NONE;
	}

	public void addListener(Listener listener)
	{
		listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
	}

	public void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	private synchronized boolean send(OnlineMessageType type, JsonObject payload)
	{
		return status == Status.READY && socket != null
			&& socket.send(gson.toJson(new OnlineEnvelope(UUID.randomUUID().toString(), type, payload)));
	}

	private boolean sendAuthenticationBegin(WebSocket webSocket)
	{
		JsonObject payload = new JsonObject();
		payload.addProperty("ign", displayName);
		payload.addProperty("publicKey", deviceKey.getPublicKeyBase64Url());
		return webSocket.send(gson.toJson(new OnlineEnvelope(UUID.randomUUID().toString(),
			OnlineMessageType.AUTH_BEGIN, payload)));
	}

	private synchronized void stopSocket()
	{
		WebSocket active = socket;
		socket = null;
		if (active != null) active.close(1000, "plugin stopped");
	}

	private JsonObject deckPayload(Deck deck)
	{
		Objects.requireNonNull(deck, "deck");
		JsonObject payload = new JsonObject();
		payload.addProperty("deckId", required(deck.getId(), 64, "deck ID"));
		JsonArray cards = new JsonArray();
		for (DeckEntry entry : deck.getEntries())
		{
			JsonObject card = new JsonObject();
			card.addProperty("cardId", entry.getCardId());
			card.addProperty("count", entry.getCount());
			cards.add(card);
		}
		payload.add("cards", cards);
		return payload;
	}

	private static JsonObject commandPayload(Command command)
	{
		Objects.requireNonNull(command, "command");
		JsonObject payload = new JsonObject();
		if (command instanceof PlayCardCommand)
		{
			PlayCardCommand play = (PlayCardCommand) command;
			payload.addProperty("type", "PLAY");
			payload.addProperty("cardId", play.getCardId());
			play.getTargetInstanceId().ifPresent(target -> payload.addProperty("target", target));
		}
		else if (command instanceof AttackCommand)
		{
			AttackCommand attack = (AttackCommand) command;
			payload.addProperty("type", "ATTACK");
			payload.addProperty("attacker", attack.getAttackerInstanceId());
			attack.getTargetInstanceId().ifPresent(target -> payload.addProperty("target", target));
		}
		else if (command instanceof EndTurnCommand) payload.addProperty("type", "END_TURN");
		else if (command instanceof ConcedeCommand) payload.addProperty("type", "CONCEDE");
		else if (command instanceof MulliganCommand)
		{
			payload.addProperty("type", "MULLIGAN");
			payload.addProperty("cardId", ((MulliganCommand) command).getCardId());
		}
		else if (command instanceof FinishMulliganCommand) payload.addProperty("type", "FINISH_MULLIGAN");
		else throw new IllegalArgumentException("unsupported command");
		return payload;
	}

	private void publishStatus(long eventGeneration, String message)
	{
		Status current;
		synchronized (this) { current = status; }
		reconnectExecutor.execute(() ->
		{
			for (Listener listener : listeners)
			{
				try { listener.onStatusChanged(eventGeneration, current, message); }
				catch (RuntimeException ignored) { }
			}
		});
	}

	private void publishMessage(long eventGeneration, OnlineEnvelope envelope)
	{
		reconnectExecutor.execute(() ->
		{
			for (Listener listener : listeners)
			{
				try { listener.onMessage(eventGeneration, envelope); }
				catch (RuntimeException ignored) { }
			}
		});
	}

	private static String required(String value, int maximum, String name)
	{
		if (value == null || value.trim().isEmpty() || value.trim().length() > maximum)
			throw new IllegalArgumentException(name + " is invalid");
		return value.trim();
	}

	private final class SocketListener extends WebSocketListener
	{
		private final long listenerGeneration;
		private final int reconnectAttempt;

		private SocketListener(long listenerGeneration, int reconnectAttempt)
		{
			this.listenerGeneration = listenerGeneration;
			this.reconnectAttempt = reconnectAttempt;
		}

		@Override
		public void onOpen(WebSocket webSocket, Response response)
		{
			synchronized (OnlineBattleClient.this)
			{
				if (listenerGeneration != generation || socket != webSocket) return;
				if (reconnectToken != null)
				{
					JsonObject payload = new JsonObject();
					payload.addProperty("reconnectToken", reconnectToken);
					webSocket.send(gson.toJson(new OnlineEnvelope(UUID.randomUUID().toString(),
						OnlineMessageType.RESUME, payload)));
					return;
				}
				sendHello(webSocket);
			}
		}

		@Override
		public void onMessage(WebSocket webSocket, String text)
		{
			if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) return;
			synchronized (OnlineBattleClient.this)
			{
				if (listenerGeneration != generation || socket != webSocket) return;
			}
			OnlineEnvelope envelope;
			try
			{
				envelope = gson.fromJson(text, OnlineEnvelope.class);
				if (envelope == null) return;
				envelope.validate();
			}
			catch (JsonParseException | IllegalArgumentException exception)
			{
				return;
			}
			String welcomeToken = null;
			boolean resumedAuthenticated = false;
			boolean compatibleWelcome = true;
			if (envelope.getType() == OnlineMessageType.WELCOME)
			{
				try
				{
					welcomeToken = required(envelope.getPayload().get("reconnectToken").getAsString(), 64,
						"reconnect token");
					resumedAuthenticated = envelope.getPayload().has("authenticated")
						&& envelope.getPayload().get("authenticated").getAsBoolean();
					compatibleWelcome = catalog.getSha256().equals(
						required(envelope.getPayload().get("catalogHash").getAsString(), 64, "catalog hash"))
						&& envelope.getPayload().get("rulesetVersion").getAsInt() == catalog.getRulesetVersion();
				}
				catch (RuntimeException exception) { return; }
			}
			if (envelope.getType() == OnlineMessageType.AUTH_CHALLENGE)
			{
				synchronized (OnlineBattleClient.this)
				{
					if (listenerGeneration != generation || socket != webSocket) return;
					try { sendAuthenticationResponse(webSocket, envelope.getPayload()); }
					catch (GeneralSecurityException | RuntimeException exception)
					{
						pendingRankedDeck = null;
						status = Status.READY;
						publishStatus(listenerGeneration, "Could not sign ranked authentication challenge");
					}
				}
				return;
			}
			synchronized (OnlineBattleClient.this)
			{
				if (listenerGeneration != generation || socket != webSocket) return;
				if (envelope.getType() == OnlineMessageType.WELCOME)
				{
					if (!compatibleWelcome)
					{
						reconnectToken = null;
						status = Status.ERROR;
						webSocket.close(1002, "incompatible server");
						return;
					}
					reconnectToken = welcomeToken;
					rankedAuthenticated = resumedAuthenticated;
					status = Status.READY;
					if (pendingRankedDeck != null)
					{
						if (rankedAuthenticated)
						{
							Deck pending = pendingRankedDeck;
							pendingRankedDeck = null;
							JsonObject payload = new JsonObject();
							payload.add("deck", deckPayload(pending));
							send(OnlineMessageType.RANKED_QUEUE_JOIN, payload);
						}
						else
						{
							status = Status.AUTHENTICATING;
							if (!sendAuthenticationBegin(webSocket))
							{
								pendingRankedDeck = null;
								status = Status.READY;
							}
						}
					}
				}
				else if (envelope.getType() == OnlineMessageType.AUTHENTICATED)
				{
					rankedAuthenticated = true;
					status = Status.READY;
					Deck pending = pendingRankedDeck;
					pendingRankedDeck = null;
					if (pending != null)
					{
						JsonObject payload = new JsonObject();
						payload.add("deck", deckPayload(pending));
						send(OnlineMessageType.RANKED_QUEUE_JOIN, payload);
					}
				}
				else if (envelope.getType() == OnlineMessageType.ERROR && status == Status.CONNECTING
					&& reconnectToken != null)
				{
					reconnectToken = null;
					waitingState = WaitingState.NONE;
					pendingRankedDeck = null;
					sendHello(webSocket);
					return;
				}
				else if (envelope.getType() == OnlineMessageType.ERROR && status == Status.AUTHENTICATING)
				{
					pendingRankedDeck = null;
					status = Status.READY;
				}
				updateWaitingState(envelope);
			}
			synchronized (OnlineBattleClient.this)
			{
				if (listenerGeneration != generation || socket != webSocket) return;
				if (envelope.getType() == OnlineMessageType.WELCOME
					|| envelope.getType() == OnlineMessageType.AUTHENTICATED) publishStatus(listenerGeneration, null);
				publishMessage(listenerGeneration, envelope);
			}
		}

		private void sendAuthenticationResponse(WebSocket webSocket, JsonObject challenge)
			throws GeneralSecurityException
		{
			String challengeId = required(challenge.get("challengeId").getAsString(), 64, "challenge ID");
			byte[] nonce = Base64.getUrlDecoder().decode(required(challenge.get("nonce").getAsString(), 128, "nonce"));
			byte[] publicKey = Base64.getUrlDecoder().decode(deviceKey.getPublicKeyBase64Url());
			byte[] signature = deviceKey.sign(RankedAuthentication.challenge(reconnectServerUrl, challengeId, nonce,
				displayName, publicKey));
			JsonObject payload = new JsonObject();
			payload.addProperty("challengeId", challengeId);
			payload.addProperty("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
			webSocket.send(gson.toJson(new OnlineEnvelope(UUID.randomUUID().toString(),
				OnlineMessageType.AUTH_RESPONSE, payload)));
		}

		private void sendHello(WebSocket webSocket)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("displayName", displayName);
			payload.addProperty("catalogHash", catalog.getSha256());
			payload.addProperty("rulesetVersion", catalog.getRulesetVersion());
			webSocket.send(gson.toJson(new OnlineEnvelope(UUID.randomUUID().toString(),
				OnlineMessageType.HELLO, payload)));
		}

		@Override
		public void onFailure(WebSocket webSocket, Throwable error, Response response)
		{
			long failedGeneration;
			synchronized (OnlineBattleClient.this)
			{
				if (listenerGeneration != generation || socket != webSocket) return;
				socket = null;
				status = Status.DISCONNECTED;
				if (waitingState == WaitingState.CASUAL_QUEUE || waitingState == WaitingState.RANKED_QUEUE)
					waitingState = WaitingState.NONE;
				failedGeneration = generation;
			}
			publishStatus(listenerGeneration, error == null ? "Connection failed" : error.getMessage());
			scheduleReconnect(failedGeneration, reconnectAttempt + 1);
		}

		@Override
		public void onClosed(WebSocket webSocket, int code, String reason)
		{
			onFailure(webSocket, null, null);
		}
	}

	private synchronized void updateWaitingState(OnlineEnvelope envelope)
	{
		JsonObject payload = envelope.getPayload();
		switch (envelope.getType())
		{
			case LOBBY_CREATED: waitingState = WaitingState.LOBBY; break;
			case LOBBY_LEAVE: waitingState = WaitingState.NONE; break;
			case QUEUE_STATUS:
				waitingState = "WAITING".equals(optionalString(payload, "status"))
					? WaitingState.CASUAL_QUEUE : WaitingState.NONE;
				break;
			case RANKED_QUEUE_STATUS:
				waitingState = "WAITING".equals(optionalString(payload, "status"))
					? WaitingState.RANKED_QUEUE : WaitingState.NONE;
				break;
			case MATCH_FOUND: waitingState = WaitingState.NONE; break;
			default: break;
		}
	}

	private void scheduleReconnect(long failedGeneration, int attempt)
	{
		if (attempt > 7) return;
		long delaySeconds = Math.min(15L, 1L << Math.min(4, attempt - 1));
		reconnectExecutor.schedule(() -> reconnect(failedGeneration, attempt), delaySeconds, TimeUnit.SECONDS);
	}

	private synchronized void reconnect(long failedGeneration, int attempt)
	{
		if (generation != failedGeneration || status != Status.DISCONNECTED
			|| reconnectServerUrl == null || displayName == null || catalog == null || deviceKey == null) return;
		long activeGeneration = ++generation;
		status = Status.CONNECTING;
		publishStatus(generation, "Reconnecting");
		Request request = new Request.Builder().url(reconnectServerUrl).build();
		socket = httpClient.newWebSocket(request, new SocketListener(activeGeneration, attempt));
	}

	private static String optionalString(JsonObject payload, String key)
	{
		return payload != null && payload.has(key) && payload.get(key).isJsonPrimitive()
			? payload.get(key).getAsString() : null;
	}

	public enum Status { DISCONNECTED, CONNECTING, AUTHENTICATING, READY, ERROR }
	private enum WaitingState { NONE, LOBBY, CASUAL_QUEUE, RANKED_QUEUE }

	public interface Listener
	{
		void onStatusChanged(long generation, Status status, String message);
		void onMessage(long generation, OnlineEnvelope envelope);
	}
}
