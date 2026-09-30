package com.osrstcgbattles.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.backend.ranked.RankedRepository;
import com.osrstcgbattles.online.OnlineEnvelope;
import com.osrstcgbattles.online.OnlineMessageType;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Process and transport boundary for the authoritative Duelscape service. */
public final class DuelscapeBackend implements AutoCloseable
{
	public static final int PROTOCOL_VERSION = OnlineEnvelope.PROTOCOL_VERSION;
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_PORT = 8787;
	private static final int MAX_MESSAGE_BYTES = 64 * 1024;
	private static final byte[] HEALTH_RESPONSE = ("{\"status\":\"ok\",\"protocolVersion\":"
		+ PROTOCOL_VERSION + ",\"rulesetVersion\":" + DuelscapeEngine.RULESET_VERSION + "}")
		.getBytes(StandardCharsets.UTF_8);
	private static final byte[] UNHEALTHY_RESPONSE = "{\"status\":\"unhealthy\"}".getBytes(StandardCharsets.UTF_8);

	private final String host;
	private final int requestedPort;
	private final Gson gson;
	private final OnlineGameService games;
	private final ScheduledExecutorService maintenance;
	private Undertow server;
	private InetSocketAddress address;

	private DuelscapeBackend(String host, int port, Path databasePath)
	{
		this.host = host;
		this.requestedPort = port;
		this.gson = new Gson();
		String rankedAudience = System.getenv().getOrDefault("DUELSCAPE_RANKED_AUDIENCE",
			"wss://play.deargod.live/v1/ws");
		this.games = new OnlineGameService(gson, System::currentTimeMillis,
			new RankedRepository(databasePath), rankedAudience);
		this.maintenance = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "duelscape-maintenance");
			thread.setDaemon(true);
			return thread;
		});
	}

	public static DuelscapeBackend create(String host, int port)
	{
		try
		{
			Path database = java.nio.file.Files.createTempFile("duelscape-backend-test-", ".db");
			database.toFile().deleteOnExit();
			return create(host, port, database);
		}
		catch (java.io.IOException exception)
		{
			throw new IllegalStateException("Could not create temporary backend database", exception);
		}
	}

	public static DuelscapeBackend create(String host, int port, Path databasePath)
	{
		if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("host is required");
		if (port < 0 || port > 65_535) throw new IllegalArgumentException("port is out of range");
		return new DuelscapeBackend(host.trim(), port, databasePath);
	}

	public synchronized void start()
	{
		if (server != null) throw new IllegalStateException("backend is already started");
		HttpHandler websocket = Handlers.websocket(new WebSocketConnectionCallback()
		{
			@Override
			public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel)
			{
				ConsumerSender sender = new ConsumerSender(gson, channel);
				String connectionId;
				try
				{
					String forwarded = exchange.getRequestHeader("X-Forwarded-For");
					String source = forwarded == null || forwarded.isBlank()
						? channel.getSourceAddress().getAddress().getHostAddress()
						: forwarded.split(",", 2)[0].trim();
					connectionId = games.connect(sender::send,
						() -> WebSockets.sendClose(1008, "session expired", channel, null), source);
				}
				catch (IllegalStateException exception)
				{
					WebSockets.sendClose(1013, "server is busy", channel, null);
					return;
				}
				channel.addCloseTask(closed -> games.disconnect(connectionId));
				channel.getReceiveSetter().set(new ClientMessages(gson, games, connectionId));
				channel.resumeReceives();
			}
		});
		server = Undertow.builder().addHttpListener(requestedPort, host)
			.setHandler(exchange -> route(exchange, websocket)).build();
		server.start();
		maintenance.scheduleAtFixedRate(() ->
		{
			try { games.maintenance(); }
			catch (RuntimeException exception) { System.err.println("Duelscape maintenance failed: " + exception.getMessage()); }
		}, 10, 10, TimeUnit.SECONDS);
		Undertow.ListenerInfo listener = server.getListenerInfo().get(0);
		address = (InetSocketAddress) listener.getAddress();
	}

	public synchronized InetSocketAddress getAddress()
	{
		if (address == null) throw new IllegalStateException("backend is not started");
		return address;
	}

	@Override
	public synchronized void close()
	{
		if (server == null) return;
		server.stop();
		maintenance.shutdown();
		games.close();
		server = null;
		address = null;
	}

	private void route(HttpServerExchange exchange, HttpHandler websocket) throws Exception
	{
		String path = exchange.getRequestPath();
		if ("/healthz".equals(path))
		{
			health(exchange);
			return;
		}
		if ("/v1/ws".equals(path))
		{
			websocket.handleRequest(exchange);
			return;
		}
		exchange.setStatusCode(404);
		exchange.endExchange();
	}

	private void health(HttpServerExchange exchange)
	{
		if (!Methods.GET.equals(exchange.getRequestMethod()))
		{
			exchange.getResponseHeaders().put(Headers.ALLOW, "GET");
			exchange.setStatusCode(405);
			exchange.endExchange();
			return;
		}
		exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
		exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
		boolean healthy = games.isHealthy();
		if (!healthy) exchange.setStatusCode(503);
		byte[] response = healthy ? HEALTH_RESPONSE : UNHEALTHY_RESPONSE;
		exchange.getResponseSender().send(new String(response, StandardCharsets.UTF_8));
	}

	private static final class ClientMessages extends AbstractReceiveListener
	{
		private final Gson gson;
		private final OnlineGameService games;
		private final String connectionId;
		private long invalidWindowStartedNanos = System.nanoTime();
		private int invalidMessages;

		private ClientMessages(Gson gson, OnlineGameService games, String connectionId)
		{
			this.gson = gson;
			this.games = games;
			this.connectionId = connectionId;
		}

		@Override
		protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message)
		{
			OnlineEnvelope incoming;
			try
			{
				String text = message.getData();
				if (text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES)
					throw new IllegalArgumentException("message is too large");
				incoming = gson.fromJson(text, OnlineEnvelope.class);
				if (incoming == null) throw new IllegalArgumentException("message is required");
				incoming.validate();
			}
			catch (JsonParseException | IllegalArgumentException exception)
			{
				long now = System.nanoTime();
				if (now - invalidWindowStartedNanos >= TimeUnit.SECONDS.toNanos(10))
				{
					invalidWindowStartedNanos = now;
					invalidMessages = 0;
				}
				if (++invalidMessages > 20)
				{
					WebSockets.sendClose(1008, "too many invalid messages", channel, null);
					return;
				}
				send(channel, error(UUID.randomUUID().toString(), "INVALID_MESSAGE", exception.getMessage()));
				return;
			}
			games.handle(connectionId, incoming);
		}

		@Override
		protected long getMaxTextBufferSize()
		{
			return MAX_MESSAGE_BYTES;
		}

		private void send(WebSocketChannel channel, OnlineEnvelope envelope)
		{
			WebSockets.sendText(gson.toJson(envelope), channel, null);
		}

		private static OnlineEnvelope error(String requestId, String code, String message)
		{
			JsonObject payload = new JsonObject();
			payload.addProperty("code", code);
			payload.addProperty("message", message == null ? "Invalid request" : message);
			return new OnlineEnvelope(requestId, OnlineMessageType.ERROR, payload);
		}
	}

	private static final class ConsumerSender
	{
		private final Gson gson;
		private final WebSocketChannel channel;

		private ConsumerSender(Gson gson, WebSocketChannel channel)
		{
			this.gson = gson;
			this.channel = channel;
		}

		private void send(OnlineEnvelope message)
		{
			WebSockets.sendText(gson.toJson(message), channel, null);
		}
	}

	public static void main(String[] args) throws Exception
	{
		String host = environment("DUELSCAPE_BIND_HOST", DEFAULT_HOST);
		int port = parsePort(environment("DUELSCAPE_PORT", Integer.toString(DEFAULT_PORT)));
		Path database = Paths.get(environment("DUELSCAPE_DATABASE", "/var/lib/duelscape/ranked.db"));
		DuelscapeBackend backend = create(host, port, database);
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			backend.close();
			stopped.countDown();
		}, "duelscape-shutdown"));
		backend.start();
		System.out.println("Duelscape backend listening on " + backend.getAddress());
		stopped.await();
	}

	private static String environment(String name, String fallback)
	{
		String value = System.getenv(name);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	static int parsePort(String value)
	{
		try
		{
			int port = Integer.parseInt(value);
			if (port < 1 || port > 65_535) throw new IllegalArgumentException("port is out of range");
			return port;
		}
		catch (NumberFormatException exception)
		{
			throw new IllegalArgumentException("port must be an integer", exception);
		}
	}
}
