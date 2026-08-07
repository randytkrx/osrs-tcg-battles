package com.osrstcgbattles.collection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

/**
 * Push-driven ownership bridge for OSRS TCG's {@code OwnedCardNamesApiService} protocol.
 * Unknown ownership is deliberately distinct from a known empty collection.
 */
@Slf4j
@Singleton
public class OwnedCardCollectionBridge
{
	// Copied protocol strings are required because Plugin Hub plugins have isolated classloaders.
	public static final String NAMESPACE = "osrstcg";
	public static final String QUERY = "query-owned-names";
	public static final String REPLY = "owned-names";
	public static final String CHANGED = "owned-names-changed";
	public static final String KEY_OWNED_NAMES = "ownedNames";

	@FunctionalInterface
	public interface Listener
	{
		void onCollectionChanged(OwnedCardCollectionSnapshot snapshot);
	}

	private final EventBus eventBus;
	private final AtomicBoolean started = new AtomicBoolean();
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private final Object lifecycleLock = new Object();
	private final Object updateLock = new Object();
	private volatile OwnedCardCollectionSnapshot snapshot = OwnedCardCollectionSnapshot.unknown(0L);

	@Inject
	public OwnedCardCollectionBridge(EventBus eventBus)
	{
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
	}

	/** Registers for pushes and immediately asks OSRS TCG for its current state. */
	public void start()
	{
		synchronized (lifecycleLock)
		{
			if (!started.compareAndSet(false, true))
			{
				return;
			}
			try
			{
				eventBus.register(this);
				queryNow();
			}
			catch (RuntimeException ex)
			{
				started.set(false);
				eventBus.unregister(this);
				throw ex;
			}
		}
	}

	/** Unregisters the bridge and discards ownership that may belong to the old lifecycle. */
	public void stop()
	{
		synchronized (lifecycleLock)
		{
			if (!started.compareAndSet(true, false))
			{
				return;
			}
			eventBus.unregister(this);
			invalidate();
		}
	}

	public boolean isStarted()
	{
		return started.get();
	}

	/** Posts one query. Callers may choose when to retry; this service never polls. */
	public void queryNow()
	{
		if (started.get())
		{
			eventBus.post(newQueryMessage());
		}
	}

	public static PluginMessage newQueryMessage()
	{
		return new PluginMessage(NAMESPACE, QUERY);
	}

	public OwnedCardCollectionSnapshot snapshot()
	{
		return snapshot;
	}

	public void addListener(Listener listener)
	{
		listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
	}

	public void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	/** Drops current data without querying, for example before an account/profile transition. */
	public void invalidate()
	{
		OwnedCardCollectionSnapshot next;
		synchronized (updateLock)
		{
			OwnedCardCollectionSnapshot current = snapshot;
			if (!current.isKnown())
			{
				return;
			}
			next = OwnedCardCollectionSnapshot.unknown(current.getRevision() + 1L);
			snapshot = next;
		}
		notifyListeners(next);
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!started.get()
			|| event == null
			|| !NAMESPACE.equals(event.getNamespace())
			|| (!REPLY.equals(event.getName()) && !CHANGED.equals(event.getName())))
		{
			return;
		}

		Map<String, Object> data = event.getData();
		Object rawNames = data == null ? null : data.get(KEY_OWNED_NAMES);
		if (!(rawNames instanceof List))
		{
			return;
		}

		Map<String, String> validated = validate((List<?>) rawNames);
		if (validated == null)
		{
			log.debug("Ignored malformed OSRS TCG owned-names payload");
			return;
		}
		publish(validated);
	}

	private static Map<String, String> validate(List<?> names)
	{
		Map<String, String> validated = new LinkedHashMap<>();
		for (Object value : names)
		{
			if (!(value instanceof String))
			{
				return null;
			}
			String displayName = ((String) value).trim();
			if (displayName.isEmpty())
			{
				return null;
			}
			validated.putIfAbsent(displayName.toLowerCase(Locale.ROOT), displayName);
		}
		return validated;
	}

	private void publish(Map<String, String> names)
	{
		OwnedCardCollectionSnapshot next;
		synchronized (updateLock)
		{
			// A message dispatched before stop() must not restore stale ownership afterward.
			if (!started.get())
			{
				return;
			}
			OwnedCardCollectionSnapshot current = snapshot;
			if (current.isKnown() && sameNames(current, names))
			{
				return;
			}
			next = OwnedCardCollectionSnapshot.known(current.getRevision() + 1L, names);
			snapshot = next;
		}
		notifyListeners(next);
	}

	private static boolean sameNames(OwnedCardCollectionSnapshot current, Map<String, String> names)
	{
		return current.getNormalizedOwnedNames().equals(names.keySet())
			&& current.getOwnedNames().equals(new java.util.LinkedHashSet<>(names.values()));
	}

	private void notifyListeners(OwnedCardCollectionSnapshot next)
	{
		for (Listener listener : listeners)
		{
			try
			{
				listener.onCollectionChanged(next);
			}
			catch (RuntimeException ex)
			{
				log.warn("Owned-card collection listener failed", ex);
			}
		}
	}
}
