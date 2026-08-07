package com.osrstcgbattles.party;

import com.google.gson.Gson;
import com.osrstcgbattles.engine.GwentEngine;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure, single-threaded protocol coordinator. RuneLite integration is deliberately kept outside this class. */
public final class BattlePartyCoordinator
{
	public enum ReceiveResult { ACCEPTED, DUPLICATE, STALE, GAP, REJECTED }

	public static final long DELIVERY_ACK_TIMEOUT_NANOS = 10_000_000_000L;
	public static final long SESSION_TIMEOUT_NANOS = 60_000_000_000L;
	private static final int REPLAY_CAPACITY = 64;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final long localMemberId;
	private final CanonicalPartyJsonCodec codec = new CanonicalPartyJsonCodec(new Gson());
	private final List<BattlePartyEnvelope> outbound = new ArrayList<>();
	private final List<PartyApplicationMessage> inboundApplication = new ArrayList<>();
	private final Map<String, List<BattlePartyEnvelope>> cachedResponses = new LinkedHashMap<String, List<BattlePartyEnvelope>>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<BattlePartyEnvelope>> eldest)
		{
			return size() > REPLAY_CAPACITY;
		}
	};
	private Session session;

	public BattlePartyCoordinator(long localMemberId)
	{
		if (localMemberId <= 0)
		{
			throw new IllegalArgumentException("localMemberId must be positive");
		}
		this.localMemberId = localMemberId;
	}

	long getLocalMemberId() { return localMemberId; }

	public void invite(long peerMemberId, String peerDisplayName, PartyDuelMetadata metadata, long nowNanos)
	{
		requireAvailable(peerMemberId, metadata);
		startSession(UUID.randomUUID().toString(), peerMemberId, peerDisplayName, metadata, true, nowNanos);
		emitPlain(BattlePartyMessageType.INVITE);
	}

	public void accept(PartyDuelMetadata metadata)
	{
		if (session == null || session.outboundInvite || session.machine.getState() != PartySessionStateMachine.State.INVITE_ACKNOWLEDGED)
		{
			throw new IllegalStateException("there is no pending inbound invite");
		}
		session.localMetadata = requireMetadata(metadata);
		emitPlain(BattlePartyMessageType.ACCEPT);
		emitLocalKey();
	}

	public void decline()
	{
		if (session == null || session.outboundInvite || session.machine.getState() != PartySessionStateMachine.State.INVITE_ACKNOWLEDGED)
		{
			throw new IllegalStateException("there is no pending inbound invite");
		}
		emitPlain(BattlePartyMessageType.DECLINE);
		finishTerminal("Invitation declined");
	}

	public void abort(String safeReason)
	{
		if (session == null || session.machine.isTerminal())
		{
			return;
		}
		emitAbort(safeReason(safeReason));
		finishTerminal(safeReason(safeReason));
	}

	public ReceiveResult receive(long senderMemberId, String senderDisplayName, boolean eligibleSender,
		BattlePartyEnvelope message, long nowNanos)
	{
		if (!eligibleSender || senderMemberId <= 0 || senderMemberId == localMemberId || !validEnvelope(message)
			|| message.getRecipientMemberId() != localMemberId || !validShape(message))
		{
			return ReceiveResult.REJECTED;
		}
		if (message.getMessageType() == BattlePartyMessageType.INVITE_ACK && message.getAckSequence() != 1)
		{
			return ReceiveResult.REJECTED;
		}

		boolean sameSession = session != null && session.matchId.equals(message.getMatchId())
			&& session.peerMemberId == senderMemberId;
		if (session == null || (session.machine.isTerminal() && !sameSession))
		{
			if (message.getMessageType() != BattlePartyMessageType.INVITE || message.getSequence() != 1
				|| message.getAckSequence() != 0)
			{
				return ReceiveResult.REJECTED;
			}
			startSession(message.getMatchId(), senderMemberId, senderDisplayName, null, false, nowNanos);
		}
		else if (!sameSession)
		{
			if (message.getMessageType() == BattlePartyMessageType.INVITE && message.getSequence() == 1)
			{
				emitBusyDecline(senderMemberId, message);
			}
			return ReceiveResult.REJECTED;
		}
		if (message.getMessageType() == BattlePartyMessageType.ABORT
			&& ((message.getNonce() == null) == (session.sessionKey != null)))
		{
			return ReceiveResult.REJECTED;
		}
		if (message.getMessageType() == BattlePartyMessageType.DECLINE
			&& session.machine.getState() != PartySessionStateMachine.State.INVITE_SENT
			&& session.machine.getState() != PartySessionStateMachine.State.INVITE_ACKNOWLEDGED)
		{
			return ReceiveResult.REJECTED;
		}
		Map<String, Object> applicationPayload = null;
		if (PartyApplicationMessage.isAllowedType(message.getMessageType()))
		{
			if (session.machine.getState() != PartySessionStateMachine.State.READY || session.sessionKey == null)
			{
				return ReceiveResult.REJECTED;
			}
			try
			{
				applicationPayload = codec.decodeMap(decodeUtf8(decrypt(message)));
			}
			catch (GeneralSecurityException | RuntimeException exception)
			{
				return ReceiveResult.REJECTED;
			}
		}

		if (message.getAckSequence() > session.nextOutboundSequence - 1)
		{
			return ReceiveResult.REJECTED;
		}
		PartySessionStateMachine.Result accepted = session.machine.acceptIncoming(senderMemberId, message);
		if (accepted == PartySessionStateMachine.Result.DUPLICATE)
		{
			List<BattlePartyEnvelope> response = cachedResponses.get(message.getMessageId());
			if (response != null) outbound.addAll(response);
			return ReceiveResult.DUPLICATE;
		}
		if (accepted == PartySessionStateMachine.Result.STALE)
		{
			return ReceiveResult.STALE;
		}
		if (accepted == PartySessionStateMachine.Result.GAP)
		{
			return ReceiveResult.GAP;
		}
		if (accepted != PartySessionStateMachine.Result.ACCEPTED)
		{
			return ReceiveResult.REJECTED;
		}

		int responseStart = outbound.size();
		try
		{
			handleAccepted(message, applicationPayload);
		}
		catch (GeneralSecurityException | RuntimeException exception)
		{
			emitAbort("Secure handshake failed");
			finishTerminal("Secure handshake failed");
		}
		if (outbound.size() > responseStart)
		{
			cachedResponses.put(message.getMessageId(), Collections.unmodifiableList(
				new ArrayList<>(outbound.subList(responseStart, outbound.size()))));
		}
		return ReceiveResult.ACCEPTED;
	}

	public void onTick(long nowNanos)
	{
		if (session == null || session.machine.isTerminal()) return;
		if (session.machine.getState() == PartySessionStateMachine.State.READY) return;
		if (session.outboundInvite && !session.inviteAcknowledged
			&& elapsed(nowNanos, session.startedNanos) >= DELIVERY_ACK_TIMEOUT_NANOS)
		{
			abort("Invitation delivery timed out");
		}
		else if (elapsed(nowNanos, session.startedNanos) >= SESSION_TIMEOUT_NANOS)
		{
			abort("Invitation or handshake timed out");
		}
	}

	public void onUserPart(long memberId)
	{
		if (session != null && !session.machine.isTerminal() && session.peerMemberId == memberId)
		{
			session.machine.recordLocal(BattlePartyMessageType.ABORT);
			finishTerminal("Opponent left the party");
		}
	}

	public List<BattlePartyEnvelope> drainOutbound()
	{
		List<BattlePartyEnvelope> copy = Collections.unmodifiableList(new ArrayList<>(outbound));
		outbound.clear();
		return copy;
	}

	public List<PartyApplicationMessage> drainInboundApplication()
	{
		List<PartyApplicationMessage> copy = Collections.unmodifiableList(new ArrayList<>(inboundApplication));
		inboundApplication.clear();
		return copy;
	}

	public void sendApplication(BattlePartyMessageType type, Map<String, ?> payload)
	{
		if (!PartyApplicationMessage.isAllowedType(type))
			throw new IllegalArgumentException("unsupported application message type");
		if (session == null || session.machine.getState() != PartySessionStateMachine.State.READY
			|| session.sessionKey == null)
			throw new IllegalStateException("application transport is not ready");
		String plaintext = codec.encodeMap(Objects.requireNonNull(payload, "payload"));
		BattlePartyEnvelope message = newEnvelope(type);
		try
		{
			encrypt(message, plaintext);
		}
		catch (GeneralSecurityException exception)
		{
			throw new IllegalStateException("unable to encrypt application message", exception);
		}
		if (type == BattlePartyMessageType.CONCEDE)
		{
			outbound.add(message);
			if (session.machine.recordLocal(type) != PartySessionStateMachine.Result.ACCEPTED)
			{
				outbound.remove(outbound.size() - 1);
				throw new IllegalStateException("invalid local protocol transition");
			}
			finishTerminal("You conceded the duel");
			return;
		}
		recordAndQueue(message);
	}

	public PartyDuelSnapshot snapshot()
	{
		if (session == null) return PartyDuelSnapshot.idle();
		PartyDuelSnapshot.Status status;
		boolean accept = false;
		boolean decline = false;
		boolean abort = false;
		PartyDuelMetadata visible = null;
		PartyDuelMetadata visiblePeer = null;
		if (session.machine.isTerminal())
		{
			status = PartyDuelSnapshot.Status.TERMINAL;
		}
		else if (session.machine.getState() == PartySessionStateMachine.State.READY)
		{
			status = PartyDuelSnapshot.Status.READY;
			abort = true;
			visible = session.localMetadata;
			visiblePeer = session.peerMetadata;
		}
		else if (!session.outboundInvite && session.machine.getState() == PartySessionStateMachine.State.INVITE_ACKNOWLEDGED)
		{
			status = PartyDuelSnapshot.Status.INBOUND_INVITE;
			accept = decline = abort = true;
		}
		else if (session.outboundInvite && (session.machine.getState() == PartySessionStateMachine.State.INVITE_SENT
			|| session.machine.getState() == PartySessionStateMachine.State.INVITE_ACKNOWLEDGED))
		{
			status = PartyDuelSnapshot.Status.OUTBOUND_INVITE;
			abort = true;
		}
		else
		{
			status = PartyDuelSnapshot.Status.HANDSHAKE;
			abort = true;
		}
		return new PartyDuelSnapshot(status, session.matchId, session.peerMemberId, session.peerDisplayName,
			statusText(status), accept, decline, abort, visible == null ? null : visible.getCatalogHash(),
			visible == null ? 0 : visible.getRulesetVersion(), visible == null ? null : visible.getDeckId(),
			visible == null ? null : visible.getDeckCommitment(),
			visiblePeer == null ? null : visiblePeer.getDeckId(),
			visiblePeer == null ? null : visiblePeer.getDeckCommitment());
	}

	byte[] sessionKeyForTesting()
	{
		return session == null || session.sessionKey == null ? null : Arrays.copyOf(session.sessionKey, session.sessionKey.length);
	}

	private void handleAccepted(BattlePartyEnvelope message, Map<String, Object> applicationPayload)
		throws GeneralSecurityException
	{
		switch (message.getMessageType())
		{
			case INVITE:
				emitPlain(BattlePartyMessageType.INVITE_ACK);
				break;
			case INVITE_ACK:
				if (message.getAckSequence() != 1) throw new IllegalArgumentException("invalid invite acknowledgement");
				session.inviteAcknowledged = true;
				break;
			case ACCEPT:
				emitLocalKey();
				break;
			case KEY:
				receiveKey(message);
				break;
			case READY:
				receiveReady(message);
				break;
			case DECLINE:
				finishTerminal("Invitation declined by opponent");
				break;
			case ABORT:
				if (message.getCiphertext() != null && session.sessionKey != null)
				{
					decrypt(message); // Authenticate it; remote text is intentionally not displayed.
				}
				finishTerminal("Opponent aborted the duel");
				break;
			case ACTION:
			case ACTION_ACK:
			case SNAPSHOT_REQUEST:
			case SNAPSHOT:
			case RESUME:
			case CONCEDE:
				inboundApplication.add(new PartyApplicationMessage(message.getMessageType(), session.matchId,
					session.peerMemberId, message.getSequence(), applicationPayload));
				if (message.getMessageType() == BattlePartyMessageType.CONCEDE)
					finishTerminal("Opponent conceded the duel");
				break;
			default:
				throw new IllegalArgumentException("unsupported message");
		}
	}

	private void receiveKey(BattlePartyEnvelope message) throws GeneralSecurityException
	{
		if (session.keyPair == null) emitLocalKey();
		PublicKey peerKey = BattlePartyCrypto.decodePublicKey(Base64.getDecoder().decode(message.getHandshakePublicKey()));
		session.peerSalt = Base64.getDecoder().decode(message.getHandshakeSalt());
		byte[] combinedSalt = BattlePartyCrypto.combineHandshakeSalts(session.localSalt, localMemberId,
			session.peerSalt, session.peerMemberId);
		session.sessionKey = BattlePartyCrypto.deriveSessionKey(session.keyPair.getPrivate(), peerKey, combinedSalt,
			session.matchId, localMemberId, session.peerMemberId);
		Arrays.fill(combinedSalt, (byte) 0);
		emitReady();
	}

	private void receiveReady(BattlePartyEnvelope message) throws GeneralSecurityException
	{
		Map<String, Object> values = codec.decodeMap(decodeUtf8(decrypt(message)));
		if (values.size() != 4 || !(values.get("catalogHash") instanceof String)
			|| !(values.get("rulesetVersion") instanceof Number) || !(values.get("deckId") instanceof String)
			|| !(values.get("deckCommitment") instanceof String))
		{
			throw new IllegalArgumentException("invalid ready payload");
		}
		double ruleset = ((Number) values.get("rulesetVersion")).doubleValue();
		if (ruleset != Math.rint(ruleset)) throw new IllegalArgumentException("invalid ruleset");
		PartyDuelMetadata peer = new PartyDuelMetadata((String) values.get("catalogHash"), (int) ruleset,
			(String) values.get("deckId"), (String) values.get("deckCommitment"));
		if (!session.localMetadata.getCatalogHash().equals(peer.getCatalogHash())
			|| session.localMetadata.getRulesetVersion() != peer.getRulesetVersion())
		{
			emitAbort("Catalog or ruleset mismatch");
			finishTerminal("Catalog or ruleset mismatch");
		}
		else
		{
			session.peerMetadata = peer;
		}
	}

	private void emitLocalKey()
	{
		try
		{
			session.keyPair = BattlePartyCrypto.generateEphemeralKeyPair();
			session.localSalt = new byte[32];
			RANDOM.nextBytes(session.localSalt);
			BattlePartyEnvelope message = newEnvelope(BattlePartyMessageType.KEY);
			message.setHandshakePublicKey(Base64.getEncoder().encodeToString(session.keyPair.getPublic().getEncoded()));
			message.setHandshakeSalt(Base64.getEncoder().encodeToString(session.localSalt));
			recordAndQueue(message);
		}
		catch (GeneralSecurityException exception)
		{
			throw new IllegalStateException("unable to create secure handshake", exception);
		}
	}

	private void emitReady() throws GeneralSecurityException
	{
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("catalogHash", session.localMetadata.getCatalogHash());
		payload.put("rulesetVersion", session.localMetadata.getRulesetVersion());
		payload.put("deckId", session.localMetadata.getDeckId());
		payload.put("deckCommitment", session.localMetadata.getDeckCommitment());
		BattlePartyEnvelope message = newEnvelope(BattlePartyMessageType.READY);
		PartySessionStateMachine.Result result = session.machine.recordLocal(BattlePartyMessageType.READY);
		if (result != PartySessionStateMachine.Result.ACCEPTED) throw new IllegalStateException("invalid READY state");
		encrypt(message, codec.encodeMap(payload));
		outbound.add(message);
	}

	private void emitAbort(String reason)
	{
		BattlePartyEnvelope message = newEnvelope(BattlePartyMessageType.ABORT);
		if (session.machine.recordLocal(BattlePartyMessageType.ABORT) != PartySessionStateMachine.Result.ACCEPTED) return;
		if (session.sessionKey != null)
		{
			Map<String, Object> payload = Collections.singletonMap("reason", reason);
			try
			{
				encrypt(message, codec.encodeMap(payload));
			}
			catch (GeneralSecurityException exception)
			{
				return;
			}
		}
		outbound.add(message);
	}

	private void emitPlain(BattlePartyMessageType type)
	{
		recordAndQueue(newEnvelope(type));
	}

	private void recordAndQueue(BattlePartyEnvelope message)
	{
		if (session.machine.recordLocal(message.getMessageType()) != PartySessionStateMachine.Result.ACCEPTED)
		{
			throw new IllegalStateException("invalid local protocol transition");
		}
		outbound.add(message);
	}

	private BattlePartyEnvelope newEnvelope(BattlePartyMessageType type)
	{
		return new BattlePartyEnvelope(BattlePartyEnvelope.CURRENT_PROTOCOL_VERSION, UUID.randomUUID().toString(),
			session.matchId, session.peerMemberId, session.nextOutboundSequence++,
			session.machine.getNextInboundSequence() - 1, type);
	}

	private void encrypt(BattlePartyEnvelope message, String plaintext) throws GeneralSecurityException
	{
		EncryptedPartyPayload encrypted = BattlePartyCrypto.encrypt(session.sessionKey,
			plaintext.getBytes(StandardCharsets.UTF_8), message, localMemberId);
		message.setNonce(Base64.getEncoder().encodeToString(encrypted.getNonce()));
		message.setCiphertext(Base64.getEncoder().encodeToString(encrypted.getCiphertext()));
	}

	private byte[] decrypt(BattlePartyEnvelope message) throws GeneralSecurityException
	{
		return BattlePartyCrypto.decrypt(session.sessionKey, new EncryptedPartyPayload(
			Base64.getDecoder().decode(message.getNonce()), Base64.getDecoder().decode(message.getCiphertext())),
			message, session.peerMemberId);
	}

	private static String decodeUtf8(byte[] bytes)
	{
		try
		{
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException exception)
		{
			throw new IllegalArgumentException("payload is not valid UTF-8", exception);
		}
	}

	private void startSession(String matchId, long peerMemberId, String displayName, PartyDuelMetadata metadata,
		boolean outboundInvite, long nowNanos)
	{
		if (peerMemberId <= 0 || peerMemberId == localMemberId) throw new IllegalArgumentException("invalid peer");
		session = new Session(matchId, peerMemberId, displayName == null ? "<unknown>" : displayName,
			metadata, outboundInvite, nowNanos, new PartySessionStateMachine(matchId, localMemberId, peerMemberId,
			REPLAY_CAPACITY));
		cachedResponses.clear();
	}

	private void requireAvailable(long peerMemberId, PartyDuelMetadata metadata)
	{
		if (session != null && !session.machine.isTerminal()) throw new IllegalStateException("party duel is busy");
		if (peerMemberId <= 0 || peerMemberId == localMemberId) throw new IllegalArgumentException("invalid peer");
		requireMetadata(metadata);
	}

	private static PartyDuelMetadata requireMetadata(PartyDuelMetadata metadata)
	{
		if (metadata == null) throw new IllegalArgumentException("metadata is required");
		if (metadata.getRulesetVersion() != GwentEngine.RULESET_VERSION)
			throw new IllegalArgumentException("unsupported ruleset version");
		return metadata;
	}

	private static boolean validEnvelope(BattlePartyEnvelope message)
	{
		if (message == null) return false;
		try { message.validate(); return true; }
		catch (IllegalArgumentException exception) { return false; }
	}

	private static boolean validShape(BattlePartyEnvelope message)
	{
		boolean encrypted = message.getNonce() != null;
		boolean key = message.getHandshakePublicKey() != null && message.getHandshakeSalt() != null;
		switch (message.getMessageType())
		{
			case INVITE:
			case INVITE_ACK:
			case ACCEPT:
			case DECLINE:
				return !encrypted && !key && message.getHandshakePublicKey() == null && message.getHandshakeSalt() == null;
			case KEY:
				return !encrypted && key;
			case READY:
			case ACTION:
			case ACTION_ACK:
			case SNAPSHOT_REQUEST:
			case SNAPSHOT:
			case RESUME:
			case CONCEDE:
				return encrypted && !key && message.getHandshakePublicKey() == null && message.getHandshakeSalt() == null;
			case ABORT:
				return !key && message.getHandshakePublicKey() == null && message.getHandshakeSalt() == null;
			default:
				return false;
		}
	}

	private void emitBusyDecline(long recipient, BattlePartyEnvelope invite)
	{
		outbound.add(new BattlePartyEnvelope(BattlePartyEnvelope.CURRENT_PROTOCOL_VERSION,
			UUID.randomUUID().toString(), invite.getMatchId(), recipient, 1, invite.getSequence(),
			BattlePartyMessageType.DECLINE));
	}

	private void finishTerminal(String reason)
	{
		session.terminalReason = reason;
		if (session.sessionKey != null) Arrays.fill(session.sessionKey, (byte) 0);
		if (session.localSalt != null) Arrays.fill(session.localSalt, (byte) 0);
		if (session.peerSalt != null) Arrays.fill(session.peerSalt, (byte) 0);
		session.sessionKey = null;
		session.keyPair = null;
	}

	private String statusText(PartyDuelSnapshot.Status status)
	{
		if (status == PartyDuelSnapshot.Status.TERMINAL) return session.terminalReason;
		if (status == PartyDuelSnapshot.Status.OUTBOUND_INVITE)
			return session.inviteAcknowledged ? "Invitation delivered; waiting for response" : "Sending invitation";
		if (status == PartyDuelSnapshot.Status.INBOUND_INVITE) return "Party duel invitation received";
		if (status == PartyDuelSnapshot.Status.HANDSHAKE) return "Establishing secure party duel";
		if (status == PartyDuelSnapshot.Status.READY) return "Secure party duel transport ready";
		return "No party duel in progress";
	}

	private static String safeReason(String reason)
	{
		if (reason == null || reason.isEmpty() || reason.length() > 128) return "Party duel aborted";
		for (int i = 0; i < reason.length(); i++)
			if (reason.charAt(i) < 0x20 || reason.charAt(i) > 0x7e) return "Party duel aborted";
		return reason;
	}

	private static long elapsed(long now, long then) { return now >= then ? now - then : 0; }

	private static final class Session
	{
		private final String matchId;
		private final long peerMemberId;
		private final String peerDisplayName;
		private final boolean outboundInvite;
		private final long startedNanos;
		private final PartySessionStateMachine machine;
		private PartyDuelMetadata localMetadata;
		private PartyDuelMetadata peerMetadata;
		private long nextOutboundSequence = 1;
		private boolean inviteAcknowledged;
		private KeyPair keyPair;
		private byte[] localSalt;
		private byte[] peerSalt;
		private byte[] sessionKey;
		private String terminalReason = "Party duel ended";

		private Session(String matchId, long peerMemberId, String peerDisplayName, PartyDuelMetadata localMetadata,
			boolean outboundInvite, long startedNanos, PartySessionStateMachine machine)
		{
			this.matchId = matchId;
			this.peerMemberId = peerMemberId;
			this.peerDisplayName = peerDisplayName;
			this.localMetadata = localMetadata;
			this.outboundInvite = outboundInvite;
			this.startedNanos = startedNanos;
			this.machine = machine;
		}
	}
}
