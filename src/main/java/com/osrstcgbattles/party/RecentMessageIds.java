package com.osrstcgbattles.party;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed-capacity insertion-ordered replay cache. */
public final class RecentMessageIds
{
	private final int capacity;
	private final Map<String, Boolean> ids;

	public RecentMessageIds(int capacity)
	{
		if (capacity <= 0 || capacity > 65_536)
		{
			throw new IllegalArgumentException("capacity is out of bounds");
		}
		this.capacity = capacity;
		this.ids = new LinkedHashMap<>();
	}

	/** Returns false when the ID was already present. */
	public synchronized boolean add(String messageId)
	{
		if (messageId == null || messageId.isEmpty() || messageId.length() > BattlePartyEnvelope.MAX_ID_LENGTH)
		{
			throw new IllegalArgumentException("invalid messageId");
		}
		if (ids.containsKey(messageId))
		{
			return false;
		}
		ids.put(messageId, Boolean.TRUE);
		if (ids.size() > capacity)
		{
			String oldest = ids.keySet().iterator().next();
			ids.remove(oldest);
		}
		return true;
	}

	public synchronized boolean contains(String messageId)
	{
		return ids.containsKey(messageId);
	}

	public synchronized int size()
	{
		return ids.size();
	}
}
