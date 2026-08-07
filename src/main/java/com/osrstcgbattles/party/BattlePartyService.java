package com.osrstcgbattles.party;

import com.google.gson.Gson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserPart;

/** Live RuneLite adapter for the pure battle-party protocol coordinator. */
@Singleton
public final class BattlePartyService
{
	private static final int MAX_QUEUED_APPLICATIONS = 128;

	private final PartyService partyService;
	private final WSClient wsClient;
	private final EventBus eventBus;
	private final ClientThread clientThread;
	private final CanonicalPartyJsonCodec applicationCodec = new CanonicalPartyJsonCodec(new Gson());
	private final Deque<QueuedApplication> applicationQueue = new ArrayDeque<>();
	private final CopyOnWriteArrayList<BattlePartyListener> listeners = new CopyOnWriteArrayList<>();
	private volatile PartyDuelSnapshot snapshot = PartyDuelSnapshot.idle();
	private volatile List<PartyOpponent> eligibleOpponents = Collections.emptyList();
	private volatile boolean started;
	private BattlePartyCoordinator coordinator;

	@Inject
	public BattlePartyService(PartyService partyService, WSClient wsClient, EventBus eventBus, ClientThread clientThread)
	{
		this.partyService = partyService;
		this.wsClient = wsClient;
		this.eventBus = eventBus;
		this.clientThread = clientThread;
	}

	public synchronized void start()
	{
		if (started) return;
		wsClient.registerMessage(BattlePartyEnvelope.class);
		try
		{
			eventBus.register(this);
			started = true;
		}
		catch (RuntimeException exception)
		{
			wsClient.unregisterMessage(BattlePartyEnvelope.class);
			throw exception;
		}
		clientThread.invoke(this::refreshPartyContext);
	}

	public synchronized void stop()
	{
		if (!started) return;
		started = false;
		applicationQueue.clear();
		try
		{
			eventBus.unregister(this);
		}
		finally
		{
			wsClient.unregisterMessage(BattlePartyEnvelope.class);
		}
		clientThread.invoke(() ->
		{
			if (started) return;
			coordinator = null;
			eligibleOpponents = Collections.emptyList();
			publish(PartyDuelSnapshot.idle());
		});
	}

	public void invite(long recipientMemberId, String catalogHash, int rulesetVersion, String deckId,
		String deckCommitment)
	{
		PartyDuelMetadata metadata = new PartyDuelMetadata(catalogHash, rulesetVersion, deckId, deckCommitment);
		clientThread.invoke(() ->
		{
			if (!started) return;
			PartyMember local = requireLocalMember();
			PartyMember peer = requireEligiblePeer(recipientMemberId, local.getMemberId());
			ensureCoordinator(local.getMemberId()).invite(peer.getMemberId(), peer.getDisplayName(), metadata,
				System.nanoTime());
			flushAndPublish();
		});
	}

	public void accept(String catalogHash, int rulesetVersion, String deckId, String deckCommitment)
	{
		PartyDuelMetadata metadata = new PartyDuelMetadata(catalogHash, rulesetVersion, deckId, deckCommitment);
		clientThread.invoke(() ->
		{
			if (!started || coordinator == null) return;
			coordinator.accept(metadata);
			flushAndPublish();
		});
	}

	public void decline()
	{
		clientThread.invoke(() ->
		{
			if (!started || coordinator == null) return;
			coordinator.decline();
			flushAndPublish();
		});
	}

	public void abort()
	{
		clientThread.invoke(() ->
		{
			if (!started || coordinator == null) return;
			coordinator.abort("Party duel aborted");
			flushAndPublish();
		});
	}

	public boolean queueApplication(BattlePartyMessageType type, Map<String, ?> payload)
	{
		if (!PartyApplicationMessage.isAllowedType(type))
			throw new IllegalArgumentException("unsupported application message type");
		synchronized (this)
		{
			if (!applicationTransportAvailable()) return false;
		}
		Map<String, Object> canonical = applicationCodec.decodeMap(applicationCodec.encodeMap(payload));
		synchronized (this)
		{
			if (!applicationTransportAvailable() || applicationQueue.size() >= MAX_QUEUED_APPLICATIONS) return false;
			applicationQueue.addLast(new QueuedApplication(type, canonical));
		}
		try
		{
			clientThread.invoke(this::drainApplicationQueue);
		}
		catch (RuntimeException ignored)
		{
			// GameTick is the fallback drain; the accepted entry remains queued.
		}
		return true;
	}

	public void sendApplication(BattlePartyMessageType type, Map<String, ?> payload)
	{
		if (!queueApplication(type, payload))
			throw new IllegalStateException("application transport is unavailable");
	}

	public PartyDuelSnapshot getSnapshot() { return snapshot; }
	public List<PartyOpponent> getEligibleOpponents() { return eligibleOpponents; }
	public long getLocalMemberId()
	{
		PartyMember local = partyService.getLocalMember();
		return local == null || !local.isLoggedIn() ? 0L : local.getMemberId();
	}
	public void addListener(BattlePartyListener listener)
	{
		if (listener == null) throw new IllegalArgumentException("listener is required");
		listeners.addIfAbsent(listener);
	}
	public void removeListener(BattlePartyListener listener) { listeners.remove(listener); }

	@Subscribe
	public void onBattlePartyEnvelope(BattlePartyEnvelope message)
	{
		clientThread.invoke(() -> processEnvelope(message));
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invoke(() ->
		{
			if (!started) return;
			if (coordinator != null) coordinator.onUserPart(event.getMemberId());
			refreshEligibleOpponents();
			flushAndPublish();
		});
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invoke(this::refreshPartyContext);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		clientThread.invoke(() ->
		{
			if (!started) return;
			refreshEligibleOpponents();
			if (coordinator != null) coordinator.onTick(System.nanoTime());
			drainApplicationQueue();
			flushAndPublish();
		});
	}

	private void processEnvelope(BattlePartyEnvelope message)
	{
		if (!started || !partyService.isInParty()) return;
		PartyMember local = partyService.getLocalMember();
		PartyMember sender = partyService.getMemberById(message.getMemberId());
		if (local == null || !local.isLoggedIn() || sender == null) return;
		BattlePartyCoordinator active = ensureCoordinator(local.getMemberId());
		BattlePartyCoordinator.ReceiveResult result = active.receive(message.getMemberId(), sender.getDisplayName(),
			sender.isLoggedIn(), message, System.nanoTime());
		if (result == BattlePartyCoordinator.ReceiveResult.GAP)
		{
			active.abort("Party message sequence gap");
		}
		flushAndPublish();
	}

	private BattlePartyCoordinator ensureCoordinator(long localMemberId)
	{
		if (coordinator == null || coordinator.getLocalMemberId() != localMemberId)
		{
			coordinator = new BattlePartyCoordinator(localMemberId);
		}
		return coordinator;
	}

	private PartyMember requireLocalMember()
	{
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || local == null || !local.isLoggedIn())
			throw new IllegalStateException("not logged into a RuneLite party");
		return local;
	}

	private PartyMember requireEligiblePeer(long memberId, long localMemberId)
	{
		PartyMember peer = partyService.getMemberById(memberId);
		if (memberId == localMemberId || peer == null || !peer.isLoggedIn())
			throw new IllegalArgumentException("opponent is not an eligible party member");
		return peer;
	}

	private void refreshPartyContext()
	{
		if (!started) return;
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || local == null || !local.isLoggedIn())
		{
			if (coordinator != null)
			{
				coordinator.abort("RuneLite party is unavailable");
				coordinator.drainOutbound();
			}
			eligibleOpponents = Collections.emptyList();
			publish(coordinator == null ? PartyDuelSnapshot.idle() : coordinator.snapshot());
			return;
		}
		ensureCoordinator(local.getMemberId());
		refreshEligibleOpponents();
		flushAndPublish();
	}

	private void refreshEligibleOpponents()
	{
		PartyMember local = partyService.getLocalMember();
		if (!partyService.isInParty() || local == null)
		{
			eligibleOpponents = Collections.emptyList();
			return;
		}
		List<PartyOpponent> choices = new ArrayList<>();
		for (PartyMember member : partyService.getMembers())
		{
			if (member.getMemberId() != local.getMemberId() && member.isLoggedIn())
				choices.add(new PartyOpponent(member.getMemberId(), member.getDisplayName()));
		}
		choices.sort(Comparator.comparing(PartyOpponent::getDisplayName, String.CASE_INSENSITIVE_ORDER)
			.thenComparingLong(PartyOpponent::getMemberId));
		eligibleOpponents = Collections.unmodifiableList(choices);
	}

	private void flushAndPublish()
	{
		if (coordinator == null) return;
		for (BattlePartyEnvelope message : coordinator.drainOutbound())
		{
			if (partyService.isInParty()) partyService.send(message);
		}
		for (PartyApplicationMessage message : coordinator.drainInboundApplication())
		{
			for (BattlePartyListener listener : listeners)
			{
				try { listener.onPartyApplicationMessage(message); }
				catch (RuntimeException ignored) { /* A listener must not break transport processing. */ }
			}
		}
		publish(coordinator.snapshot());
	}

	private boolean applicationTransportAvailable()
	{
		return started && coordinator != null && snapshot.getStatus() == PartyDuelSnapshot.Status.READY;
	}

	private void drainApplicationQueue()
	{
		while (true)
		{
			synchronized (this)
			{
				if (!applicationTransportAvailable())
				{
					applicationQueue.clear();
					return;
				}
				QueuedApplication next = applicationQueue.peekFirst();
				if (next == null) return;
				try
				{
					if (!partyService.isInParty())
						throw new IllegalStateException("RuneLite party is unavailable");
					coordinator.sendApplication(next.type, next.payload);
					flushAndPublish();
					applicationQueue.removeFirst();
				}
				catch (RuntimeException exception)
				{
					applicationQueue.clear();
					try
					{
						coordinator.abort("Application transport failed");
						flushAndPublish();
					}
					catch (RuntimeException ignored)
					{
						publish(coordinator.snapshot());
					}
					return;
				}
			}
		}
	}

	private void publish(PartyDuelSnapshot next)
	{
		if (next.equals(snapshot)) return;
		snapshot = next;
		for (BattlePartyListener listener : listeners)
		{
			try { listener.onPartyDuelSnapshotChanged(next); }
			catch (RuntimeException ignored) { /* A UI listener must not break transport processing. */ }
		}
	}

	private static final class QueuedApplication
	{
		private final BattlePartyMessageType type;
		private final Map<String, Object> payload;

		private QueuedApplication(BattlePartyMessageType type, Map<String, Object> payload)
		{
			this.type = type;
			this.payload = payload;
		}
	}
}
