package com.osrstcgbattles.party;

import java.util.Map;
import java.util.Objects;

/** An authenticated application payload received from the current party peer. */
public final class PartyApplicationMessage
{
	private final BattlePartyMessageType type;
	private final String matchId;
	private final long peerMemberId;
	private final long sequence;
	private final Map<String, Object> payload;

	public PartyApplicationMessage(BattlePartyMessageType type, String matchId, long peerMemberId, long sequence,
		Map<String, ?> payload, CanonicalPartyJsonCodec codec)
	{
		if (!isAllowedType(type)) throw new IllegalArgumentException("unsupported application message type");
		if (matchId == null || matchId.isEmpty() || matchId.length() > BattlePartyEnvelope.MAX_ID_LENGTH)
			throw new IllegalArgumentException("invalid matchId");
		if (peerMemberId <= 0) throw new IllegalArgumentException("peerMemberId must be positive");
		if (sequence <= 0 || sequence > BattlePartyEnvelope.MAX_SEQUENCE)
			throw new IllegalArgumentException("sequence is out of bounds");
		Objects.requireNonNull(payload, "payload");
		this.type = type;
		this.matchId = matchId;
		this.peerMemberId = peerMemberId;
		this.sequence = sequence;
		this.payload = codec.decodeMap(codec.encodeMap(payload));
	}

	static boolean isAllowedType(BattlePartyMessageType type)
	{
		return type == BattlePartyMessageType.ACTION || type == BattlePartyMessageType.ACTION_ACK
			|| type == BattlePartyMessageType.SNAPSHOT;
	}

	public BattlePartyMessageType getType() { return type; }
	public String getMatchId() { return matchId; }
	public long getPeerMemberId() { return peerMemberId; }
	public long getSequence() { return sequence; }
	public Map<String, Object> getPayload() { return payload; }
}
