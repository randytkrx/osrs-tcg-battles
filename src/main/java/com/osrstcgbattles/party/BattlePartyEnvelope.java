package com.osrstcgbattles.party;

import java.util.Base64;
import net.runelite.client.party.messages.PartyMemberMessage;

/** The single wire message used by the battle party protocol. */
public final class BattlePartyEnvelope extends PartyMemberMessage
{
	public static final int CURRENT_PROTOCOL_VERSION = 3;
	public static final int MAX_ID_LENGTH = 128;
	public static final int MAX_CIPHERTEXT_BYTES = 24 * 1024;
	public static final long MAX_SEQUENCE = 1_000_000_000L;

	private int protocolVersion;
	private String messageId;
	private String matchId;
	private long recipientMemberId;
	private long sequence;
	private long ackSequence;
	private BattlePartyMessageType messageType;
	private String nonce;
	private String ciphertext;
	private String handshakePublicKey;
	private String handshakeSalt;

	public BattlePartyEnvelope()
	{
	}

	public BattlePartyEnvelope(int protocolVersion, String messageId, String matchId, long recipientMemberId,
		long sequence, long ackSequence, BattlePartyMessageType messageType)
	{
		this.protocolVersion = protocolVersion;
		this.messageId = messageId;
		this.matchId = matchId;
		this.recipientMemberId = recipientMemberId;
		this.sequence = sequence;
		this.ackSequence = ackSequence;
		this.messageType = messageType;
	}

	public void validate()
	{
		if (protocolVersion != CURRENT_PROTOCOL_VERSION)
		{
			throw new IllegalArgumentException("unsupported protocol version");
		}
		validateId("messageId", messageId);
		validateId("matchId", matchId);
		if (recipientMemberId <= 0)
		{
			throw new IllegalArgumentException("recipientMemberId must be positive");
		}
		if (sequence <= 0 || sequence > MAX_SEQUENCE || ackSequence < 0 || ackSequence > MAX_SEQUENCE)
		{
			throw new IllegalArgumentException("sequence is out of bounds");
		}
		if (messageType == null)
		{
			throw new IllegalArgumentException("messageType is required");
		}
		if ((nonce == null) != (ciphertext == null))
		{
			throw new IllegalArgumentException("nonce and ciphertext must occur together");
		}
		if (nonce != null)
		{
			decodeBounded("nonce", nonce, 12, 12);
			decodeBounded("ciphertext", ciphertext, 16, MAX_CIPHERTEXT_BYTES);
		}
		if (handshakePublicKey != null)
		{
			decodeBounded("handshakePublicKey", handshakePublicKey, 64, 256);
		}
		if (handshakeSalt != null)
		{
			decodeBounded("handshakeSalt", handshakeSalt, 16, 64);
		}
		if (messageType != BattlePartyMessageType.KEY
			&& (handshakePublicKey != null || handshakeSalt != null))
		{
			throw new IllegalArgumentException("handshake fields are only valid on KEY messages");
		}
	}

	private static void validateId(String name, String value)
	{
		if (value == null || value.isEmpty() || value.length() > MAX_ID_LENGTH)
		{
			throw new IllegalArgumentException(name + " is missing or too long");
		}
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (c < 0x21 || c > 0x7e)
			{
				throw new IllegalArgumentException(name + " contains an invalid character");
			}
		}
	}

	private static byte[] decodeBounded(String name, String value, int minimum, int maximum)
	{
		if (value.length() > ((maximum + 2) / 3) * 4)
		{
			throw new IllegalArgumentException(name + " is too long");
		}
		try
		{
			byte[] decoded = Base64.getDecoder().decode(value);
			if (decoded.length < minimum || decoded.length > maximum)
			{
				throw new IllegalArgumentException(name + " has an invalid length");
			}
			return decoded;
		}
		catch (IllegalArgumentException exception)
		{
			throw new IllegalArgumentException(name + " is not valid bounded base64", exception);
		}
	}

	public int getProtocolVersion() { return protocolVersion; }
	public void setProtocolVersion(int protocolVersion) { this.protocolVersion = protocolVersion; }
	public String getMessageId() { return messageId; }
	public void setMessageId(String messageId) { this.messageId = messageId; }
	public String getMatchId() { return matchId; }
	public void setMatchId(String matchId) { this.matchId = matchId; }
	public long getRecipientMemberId() { return recipientMemberId; }
	public void setRecipientMemberId(long recipientMemberId) { this.recipientMemberId = recipientMemberId; }
	public long getSequence() { return sequence; }
	public void setSequence(long sequence) { this.sequence = sequence; }
	public long getAckSequence() { return ackSequence; }
	public void setAckSequence(long ackSequence) { this.ackSequence = ackSequence; }
	public BattlePartyMessageType getMessageType() { return messageType; }
	public void setMessageType(BattlePartyMessageType messageType) { this.messageType = messageType; }
	public String getNonce() { return nonce; }
	public void setNonce(String nonce) { this.nonce = nonce; }
	public String getCiphertext() { return ciphertext; }
	public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
	public String getHandshakePublicKey() { return handshakePublicKey; }
	public void setHandshakePublicKey(String handshakePublicKey) { this.handshakePublicKey = handshakePublicKey; }
	public String getHandshakeSalt() { return handshakeSalt; }
	public void setHandshakeSalt(String handshakeSalt) { this.handshakeSalt = handshakeSalt; }
}
