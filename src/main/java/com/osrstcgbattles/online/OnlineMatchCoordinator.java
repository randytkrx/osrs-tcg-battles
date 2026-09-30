package com.osrstcgbattles.online;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.match.MatchView;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Client-side state holder; all authoritative transitions remain on the backend. */
public final class OnlineMatchCoordinator
{
	private final OnlineBattleClient client;
	private final Gson gson;
	private final BattleCardCatalog catalog;
	private final String matchId;
	private final PlayerId localSeat;
	private final String opponentDisplayName;
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile OnlineMatchSnapshot snapshot;
	private boolean commandInFlight;
	private boolean terminalEventAccepted;

	public OnlineMatchCoordinator(OnlineBattleClient client, Gson gson, BattleCardCatalog catalog,
		OnlineEnvelope matchFound)
	{
		this.client = Objects.requireNonNull(client, "client");
		this.gson = Objects.requireNonNull(gson, "gson");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		if (matchFound == null || matchFound.getType() != OnlineMessageType.MATCH_FOUND)
			throw new IllegalArgumentException("MATCH_FOUND is required");
		JsonObject payload = matchFound.getPayload();
		matchId = text(payload, "matchId");
		localSeat = PlayerId.valueOf(text(payload, "seat"));
		opponentDisplayName = text(payload, "opponentDisplayName");
		long revision = revision(payload);
		snapshot = new OnlineMatchSnapshot(OnlineMatchSnapshot.Status.WAITING_STATE, matchId, localSeat,
			opponentDisplayName, revision, null, "Waiting for authoritative match state");
	}

	public ReceiveResult receive(OnlineEnvelope envelope)
	{
		if (envelope == null) return ReceiveResult.IGNORED;
		OnlineMatchSnapshot updated = null;
		ReceiveResult result = ReceiveResult.IGNORED;
		synchronized (this)
		{
			if (envelope.getType() == OnlineMessageType.MATCH_STATE
				|| envelope.getType() == OnlineMessageType.MATCH_COMPLETE)
			{
				JsonObject payload = envelope.getPayload();
				if (!matchId.equals(text(payload, "matchId"))) return ReceiveResult.IGNORED;
				long nextRevision = revision(payload);
				OnlineMatchSnapshot current = snapshot;
				if (terminalEventAccepted
					|| nextRevision < current.getRevision()
					|| nextRevision == current.getRevision() && current.getView() != null)
					return ReceiveResult.IGNORED;
				if (payload.get("view") == null || !payload.get("view").isJsonObject())
					throw new IllegalArgumentException("missing view");
				WireMatchView wire = gson.fromJson(payload.get("view"), WireMatchView.class);
				MatchView view = wire.toMatchView(catalog);
				if (view.getViewer() != localSeat) throw new IllegalArgumentException("match viewer does not match local seat");
				boolean terminal = envelope.getType() == OnlineMessageType.MATCH_COMPLETE;
				if (terminal != (view.getStatus() == MatchStatus.COMPLETE))
					throw new IllegalArgumentException("match event does not match view status");
				OnlineMatchSnapshot.Status status = terminal
					? OnlineMatchSnapshot.Status.COMPLETE : OnlineMatchSnapshot.Status.ACTIVE;
				snapshot = new OnlineMatchSnapshot(status, matchId, localSeat, opponentDisplayName,
					nextRevision, view, terminal ? "Match complete" : "Match active");
				commandInFlight = false;
				terminalEventAccepted = terminal;
				updated = snapshot;
				result = terminal ? ReceiveResult.TERMINAL_ACCEPTED : ReceiveResult.UPDATED;
			}
			else if (envelope.getType() == OnlineMessageType.MATCH_REJECTED)
			{
				JsonObject payload = envelope.getPayload();
				if (!matchId.equals(text(payload, "matchId"))) return ReceiveResult.IGNORED;
				OnlineMatchSnapshot current = snapshot;
				long nextRevision = revision(payload);
				if (terminalEventAccepted
					|| nextRevision < current.getRevision()) return ReceiveResult.IGNORED;
				OnlineMatchSnapshot.Status status = current.getView() == null ? OnlineMatchSnapshot.Status.ERROR
					: OnlineMatchSnapshot.Status.ACTIVE;
				snapshot = new OnlineMatchSnapshot(status, matchId, localSeat,
					opponentDisplayName, nextRevision, current.getView(), "Command rejected: " + text(payload, "reason"));
				commandInFlight = false;
				updated = snapshot;
				result = ReceiveResult.UPDATED;
			}
			else if (envelope.getType() == OnlineMessageType.MATCH_ABORTED)
			{
				JsonObject payload = envelope.getPayload();
				if (!matchId.equals(text(payload, "matchId"))) return ReceiveResult.IGNORED;
				OnlineMatchSnapshot current = snapshot;
				if (terminalEventAccepted) return ReceiveResult.IGNORED;
				snapshot = new OnlineMatchSnapshot(OnlineMatchSnapshot.Status.ERROR, matchId, localSeat,
					opponentDisplayName, current.getRevision(), current.getView(), text(payload, "reason"));
				commandInFlight = false;
				terminalEventAccepted = true;
				updated = snapshot;
				result = ReceiveResult.TERMINAL_ACCEPTED;
			}
		}
		if (updated != null) publish(updated);
		return result;
	}

	public boolean submit(Command command)
	{
		long revision;
		synchronized (this)
		{
			OnlineMatchSnapshot current = snapshot;
			if (commandInFlight || current.getStatus() != OnlineMatchSnapshot.Status.ACTIVE) return false;
			commandInFlight = true;
			revision = current.getRevision();
		}
		boolean sent = client.sendCommand(matchId, revision, command);
		if (!sent) synchronized (this) { commandInFlight = false; }
		return sent;
	}

	public boolean concede()
	{
		long revision;
		synchronized (this)
		{
			OnlineMatchSnapshot current = snapshot;
			if (commandInFlight || (current.getStatus() != OnlineMatchSnapshot.Status.ACTIVE
				&& current.getStatus() != OnlineMatchSnapshot.Status.WAITING_STATE)) return false;
			commandInFlight = true;
			revision = current.getRevision();
		}
		boolean sent = client.sendCommand(matchId, revision, new ConcedeCommand(localSeat));
		if (!sent) synchronized (this) { commandInFlight = false; }
		return sent;
	}

	public OnlineMatchSnapshot snapshot() { return snapshot; }
	public void addListener(Listener listener) { listeners.addIfAbsent(Objects.requireNonNull(listener, "listener")); }
	public void removeListener(Listener listener) { listeners.remove(listener); }

	public String getMatchId() { return matchId; }

	public synchronized void transportDisconnected() { commandInFlight = false; }

	public void sessionLost(String reason)
	{
		OnlineMatchSnapshot updated;
		synchronized (this)
		{
			if (terminalEventAccepted) return;
			OnlineMatchSnapshot current = snapshot;
			snapshot = new OnlineMatchSnapshot(OnlineMatchSnapshot.Status.ERROR, matchId, localSeat,
				opponentDisplayName, current.getRevision(), current.getView(), reason);
			commandInFlight = false;
			terminalEventAccepted = true;
			updated = snapshot;
		}
		publish(updated);
	}

	private void publish(OnlineMatchSnapshot current)
	{
		for (Listener listener : listeners)
		{
			try { listener.onOnlineMatchChanged(current); }
			catch (RuntimeException ignored) { }
		}
	}

	private static long revision(JsonObject payload)
	{
		if (payload == null || !payload.has("revision") || !payload.get("revision").isJsonPrimitive()
			|| !payload.get("revision").getAsJsonPrimitive().isNumber())
			throw new IllegalArgumentException("missing revision");
		try
		{
			long value = payload.get("revision").getAsBigDecimal().longValueExact();
			if (value < 0) throw new IllegalArgumentException("invalid revision");
			return value;
		}
		catch (ArithmeticException | NumberFormatException exception)
		{
			throw new IllegalArgumentException("invalid revision", exception);
		}
	}

	private static String text(JsonObject payload, String key)
	{
		if (payload == null || !payload.has(key) || !payload.get(key).isJsonPrimitive())
			throw new IllegalArgumentException("missing " + key);
		String value = payload.get(key).getAsString();
		if (value.isEmpty()) throw new IllegalArgumentException("invalid " + key);
		return value;
	}

	public interface Listener
	{
		void onOnlineMatchChanged(OnlineMatchSnapshot snapshot);
	}

	public enum ReceiveResult { IGNORED, UPDATED, TERMINAL_ACCEPTED }
}
