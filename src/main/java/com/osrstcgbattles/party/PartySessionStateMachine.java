package com.osrstcgbattles.party;

import java.util.Objects;

/** Pure protocol ordering and handshake state; transport and cryptography are deliberately external. */
public final class PartySessionStateMachine
{
	public enum State
	{
		NEW, INVITE_SENT, INVITE_RECEIVED, INVITE_ACKNOWLEDGED, ACCEPTED, KEY_EXCHANGED, READY,
		DECLINED, ABORTED
	}

	public enum Result
	{
		ACCEPTED, DUPLICATE, STALE, GAP, WRONG_PEER, WRONG_MATCH, WRONG_RECIPIENT, INVALID_MESSAGE,
		INVALID_STATE, TERMINAL
	}

	private final String matchId;
	private final long localMemberId;
	private final long peerMemberId;
	private final RecentMessageIds recentMessageIds;
	private long nextInboundSequence = 1;
	private State state = State.NEW;
	private boolean localKey;
	private boolean peerKey;
	private boolean localReady;
	private boolean peerReady;

	public PartySessionStateMachine(String matchId, long localMemberId, long peerMemberId, int replayCapacity)
	{
		this.matchId = Objects.requireNonNull(matchId, "matchId");
		if (matchId.isEmpty() || matchId.length() > BattlePartyEnvelope.MAX_ID_LENGTH
			|| localMemberId <= 0 || peerMemberId <= 0 || localMemberId == peerMemberId)
		{
			throw new IllegalArgumentException("invalid session identity");
		}
		this.localMemberId = localMemberId;
		this.peerMemberId = peerMemberId;
		this.recentMessageIds = new RecentMessageIds(replayCapacity);
	}

	public Result acceptIncoming(long senderMemberId, BattlePartyEnvelope message)
	{
		if (senderMemberId != peerMemberId)
		{
			return Result.WRONG_PEER;
		}
		if (message == null)
		{
			return Result.INVALID_MESSAGE;
		}
		if (!matchId.equals(message.getMatchId()))
		{
			return Result.WRONG_MATCH;
		}
		if (message.getRecipientMemberId() != localMemberId)
		{
			return Result.WRONG_RECIPIENT;
		}
		try
		{
			message.validate();
		}
		catch (IllegalArgumentException exception)
		{
			return Result.INVALID_MESSAGE;
		}
		if (recentMessageIds.contains(message.getMessageId()))
		{
			return Result.DUPLICATE;
		}
		if (message.getSequence() < nextInboundSequence)
		{
			return Result.STALE;
		}
		if (message.getSequence() > nextInboundSequence)
		{
			return Result.GAP;
		}
		Result transition = transition(message.getMessageType(), false);
		if (transition == Result.ACCEPTED)
		{
			recentMessageIds.add(message.getMessageId());
			nextInboundSequence++;
		}
		return transition;
	}

	public Result recordLocal(BattlePartyMessageType type)
	{
		return transition(Objects.requireNonNull(type, "type"), true);
	}

	private Result transition(BattlePartyMessageType type, boolean local)
	{
		if (isTerminal())
		{
			return Result.TERMINAL;
		}
		if (type == BattlePartyMessageType.DECLINE)
		{
			state = State.DECLINED;
			return Result.ACCEPTED;
		}
		if (type == BattlePartyMessageType.ABORT)
		{
			state = State.ABORTED;
			return Result.ACCEPTED;
		}
		switch (type)
		{
			case INVITE:
				if (state != State.NEW) return Result.INVALID_STATE;
				state = local ? State.INVITE_SENT : State.INVITE_RECEIVED;
				return Result.ACCEPTED;
			case INVITE_ACK:
				if ((local && state != State.INVITE_RECEIVED) || (!local && state != State.INVITE_SENT))
					return Result.INVALID_STATE;
				state = State.INVITE_ACKNOWLEDGED;
				return Result.ACCEPTED;
			case ACCEPT:
				if (state != State.INVITE_ACKNOWLEDGED) return Result.INVALID_STATE;
				state = State.ACCEPTED;
				return Result.ACCEPTED;
			case KEY:
				if (state != State.ACCEPTED && state != State.KEY_EXCHANGED) return Result.INVALID_STATE;
				if (local) localKey = true; else peerKey = true;
				if (localKey && peerKey) state = State.KEY_EXCHANGED;
				return Result.ACCEPTED;
			case READY:
				if (state != State.KEY_EXCHANGED && state != State.READY) return Result.INVALID_STATE;
				if (local) localReady = true; else peerReady = true;
				if (localReady && peerReady) state = State.READY;
				return Result.ACCEPTED;
			case ACTION:
			case ACTION_ACK:
			case SNAPSHOT:
				return state == State.READY ? Result.ACCEPTED : Result.INVALID_STATE;
			default:
				return Result.INVALID_STATE;
		}
	}

	public State getState() { return state; }
	public long getNextInboundSequence() { return nextInboundSequence; }
	public boolean isTerminal()
	{
		return state == State.DECLINED || state == State.ABORTED;
	}
}
