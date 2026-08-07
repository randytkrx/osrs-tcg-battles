package com.osrstcgbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Immutable flat battlefields for both players. */
public final class BoardState
{
	private final Map<PlayerId, List<BoardUnit>> units;

	private BoardState(Map<PlayerId, List<BoardUnit>> source)
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values())
		{
			copy.put(player, Collections.unmodifiableList(new ArrayList<>(source.get(player))));
		}
		units = Collections.unmodifiableMap(copy);
	}

	static BoardState empty()
	{
		EnumMap<PlayerId, List<BoardUnit>> empty = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values()) empty.put(player, Collections.emptyList());
		return new BoardState(empty);
	}

	public List<BoardUnit> getUnits(PlayerId player)
	{
		return units.get(player);
	}

	/** @deprecated Rows no longer affect gameplay. */
	@Deprecated
	public List<BoardUnit> getRow(PlayerId player, Row row)
	{
		return row == Row.MELEE ? getUnits(player) : Collections.emptyList();
	}

	/** @deprecated Scores no longer determine a winner. */
	@Deprecated
	public int getTotalScore(PlayerId player)
	{
		return getUnits(player).stream().mapToInt(BoardUnit::getCurrentAttack).sum();
	}

	BoardState add(PlayerId player, BoardUnit unit)
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = mutableCopy();
		copy.get(player).add(unit);
		return new BoardState(copy);
	}

	BoardUnit find(String instanceId)
	{
		for (PlayerId player : PlayerId.values())
		{
			for (BoardUnit unit : getUnits(player))
			{
				if (unit.getInstanceId().equals(instanceId)) return unit;
			}
		}
		return null;
	}

	PlayerId ownerOf(String instanceId)
	{
		for (PlayerId player : PlayerId.values())
		{
			if (getUnits(player).stream().anyMatch(unit -> unit.getInstanceId().equals(instanceId))) return player;
		}
		return null;
	}

	BoardState replace(BoardUnit replacement)
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = mutableCopy();
		for (PlayerId player : PlayerId.values())
		{
			List<BoardUnit> list = copy.get(player);
			for (int i = 0; i < list.size(); i++)
			{
				if (list.get(i).getInstanceId().equals(replacement.getInstanceId()))
				{
					list.set(i, replacement);
					return new BoardState(copy);
				}
			}
		}
		return this;
	}

	BoardState remove(String instanceId)
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = mutableCopy();
		for (PlayerId player : PlayerId.values())
		{
			if (copy.get(player).removeIf(unit -> unit.getInstanceId().equals(instanceId))) return new BoardState(copy);
		}
		return this;
	}

	BoardState readyAll(PlayerId player)
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = mutableCopy();
		List<BoardUnit> list = copy.get(player);
		for (int i = 0; i < list.size(); i++) list.set(i, list.get(i).withReady(true));
		return new BoardState(copy);
	}

	private EnumMap<PlayerId, List<BoardUnit>> mutableCopy()
	{
		EnumMap<PlayerId, List<BoardUnit>> copy = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values()) copy.put(player, new ArrayList<>(getUnits(player)));
		return copy;
	}
}
