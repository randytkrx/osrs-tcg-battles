package com.osrstcgbattles.match;

import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.collection.OwnedCardCollectionStatus;
import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import com.osrstcgbattles.deck.DeckValidator;
import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.GwentEngine;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.MulliganCommand;
import com.osrstcgbattles.engine.PlayCardCommand;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.integration.CatalogCardLookup;
import com.osrstcgbattles.integration.CatalogDeckFactory;
import com.osrstcgbattles.party.BattlePartyEnvelope;
import com.osrstcgbattles.party.BattlePartyMessageType;
import com.osrstcgbattles.party.DeckCommitment;
import com.osrstcgbattles.party.PartyApplicationMessage;
import com.osrstcgbattles.party.PartyDuelSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, owner-thread coordinator for deterministic party matches. */
public final class PartyMatchCoordinator
{
	private static final byte[] SEED_DOMAIN = "OSRS-TCG-PARTY-MATCH-SEED-V1".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SEED_COMMITMENT_DOMAIN =
		"OSRS-TCG-PARTY-MATCH-SEED-COMMITMENT-V1".getBytes(StandardCharsets.US_ASCII);
	private static final long MAX_WIRE_INTEGER = 9_007_199_254_740_991L;
	private static final int MAX_ID_LENGTH = BattlePartyEnvelope.MAX_ID_LENGTH;
	// RuneLite game ticks are roughly 600 ms; allow transient reconnects without waiting forever.
	static final int RETRY_LIMIT = 10;

	private final long localMemberId;
	private final long peerMemberId;
	private final PartyDuelSnapshot duel;
	private final BattleCardCatalog catalog;
	private final DeckValidator validator;
	private final CatalogCardLookup cardLookup;
	private final CatalogDeckFactory deckFactory;
	private final Deck localDeck;
	private final PartyMatchChannel channel;
	private final long localContribution;
	private final PlayerId localSeat;
	private final PlayerId peerSeat;
	private final GwentEngine engine = new GwentEngine();
	private final List<PartyMatchListener> listeners = new ArrayList<>();

	private PartyMatchSnapshot.Status status = PartyMatchSnapshot.Status.WAITING_SETUP;
	private MatchState state;
	private long revision;
	private String peerSeedCommitment;
	private Setup peerSetup;
	private boolean started;
	private Long pendingRevision;
	private String pendingHash;
	private Map<String, Object> pendingActionPayload;
	private final Map<Long, Map<String, Object>> appliedPeerActions = new LinkedHashMap<>();
	private final Map<Long, String> acknowledgedLocalActions = new LinkedHashMap<>();
	private Map<String, Object> lastAckPayload;
	private int setupRetries;
	private int actionRetries;
	private int ackRetries;
	private String userStatus = "Waiting to exchange seed commitments";

	public PartyMatchCoordinator(long localMemberId, PartyDuelSnapshot duel, BattleCardCatalog catalog,
		DeckValidator validator, CatalogCardLookup cardLookup, CatalogDeckFactory deckFactory, Deck localDeck,
		PartyMatchChannel channel, long localSeedContribution)
	{
		if (localMemberId <= 0) throw new IllegalArgumentException("localMemberId must be positive");
		this.duel = Objects.requireNonNull(duel, "duel");
		if (duel.getStatus() != PartyDuelSnapshot.Status.READY) throw new IllegalArgumentException("duel must be READY");
		if (duel.getPeerMemberId() <= 0 || duel.getPeerMemberId() == localMemberId)
			throw new IllegalArgumentException("invalid peer member ID");
		this.localMemberId = localMemberId;
		this.peerMemberId = duel.getPeerMemberId();
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.cardLookup = Objects.requireNonNull(cardLookup, "cardLookup");
		this.deckFactory = Objects.requireNonNull(deckFactory, "deckFactory");
		this.localDeck = canonicalDeck(Objects.requireNonNull(localDeck, "localDeck"));
		this.channel = Objects.requireNonNull(channel, "channel");
		this.localContribution = localSeedContribution;
		this.localSeat = localMemberId < peerMemberId ? PlayerId.PLAYER_ONE : PlayerId.PLAYER_TWO;
		this.peerSeat = localSeat.opponent();
		validateLocalConfiguration();
	}

	public void start()
	{
		if (started || status == PartyMatchSnapshot.Status.DESYNC
			|| status == PartyMatchSnapshot.Status.ABORTED || status == PartyMatchSnapshot.Status.COMPLETE) return;
		started = true;
		sendSilently(BattlePartyMessageType.SNAPSHOT, seedCommitPayload());
		userStatus = "Waiting for opponent seed commitment";
		publish();
	}

	public CommandResult submit(Command command)
	{
		Objects.requireNonNull(command, "command");
		if (status != PartyMatchSnapshot.Status.ACTIVE) throw new IllegalStateException("match is not active");
		if (command.getPlayer() != localSeat) throw new IllegalArgumentException("command actor is not the local seat");
		if (pendingRevision != null) throw new IllegalStateException("local action is awaiting acknowledgement");

		long priorRevision = revision;
		String priorHash = state.getSynchronizationStateHash();
		CommandResult result = engine.execute(state, command);
		if (!result.isAccepted()) return result;

		MatchState nextState = result.getState();
		Map<String, Object> payload = actionPayload(command, priorRevision, priorHash,
			nextState.getSynchronizationStateHash());
		if (!channel.send(BattlePartyMessageType.ACTION, payload))
			throw new IllegalStateException("application transport is unavailable");
		state = nextState;
		revision = priorRevision + 1;
		pendingRevision = revision;
		pendingHash = nextState.getSynchronizationStateHash();
		pendingActionPayload = payload;
		actionRetries = 0;
		lastAckPayload = null;
		updateStatusFromEngine();
		publish();
		return result;
	}

	/** Advances deterministic retry timers by one caller-defined tick. */
	public void onTick()
	{
		if (status == PartyMatchSnapshot.Status.DESYNC || status == PartyMatchSnapshot.Status.ABORTED) return;

		if (pendingActionPayload != null)
		{
			if (actionRetries >= RETRY_LIMIT)
			{
				pendingRevision = null;
				pendingHash = null;
				pendingActionPayload = null;
				timeout();
				return;
			}
			sendSilently(BattlePartyMessageType.ACTION, pendingActionPayload);
			actionRetries++;
		}
		else if (started && peerSetup == null)
		{
			if (setupRetries >= RETRY_LIMIT)
			{
				timeout();
				return;
			}
			sendSilently(BattlePartyMessageType.SNAPSHOT, seedCommitPayload());
			if (peerSeedCommitment != null)
				sendSilently(BattlePartyMessageType.SNAPSHOT, setupPayload(localDeck, localContribution));
			setupRetries++;
		}
		else if (started && setupRetries < RETRY_LIMIT)
		{
			sendSilently(BattlePartyMessageType.SNAPSHOT, seedCommitPayload());
			sendSilently(BattlePartyMessageType.SNAPSHOT, setupPayload(localDeck, localContribution));
			setupRetries++;
		}

		if (lastAckPayload != null && ackRetries < RETRY_LIMIT)
		{
			sendSilently(BattlePartyMessageType.ACTION_ACK, lastAckPayload);
			ackRetries++;
		}
	}

	public void receive(PartyApplicationMessage message)
	{
		if (message == null || status == PartyMatchSnapshot.Status.DESYNC
			|| status == PartyMatchSnapshot.Status.ABORTED) return;
		if (!duel.getMatchId().equals(message.getMatchId()) || message.getPeerMemberId() != peerMemberId)
		{
			fail(message.getType() == BattlePartyMessageType.SNAPSHOT && state == null,
				"Match synchronization failed");
			return;
		}
		try
		{
			switch (message.getType())
			{
				case SNAPSHOT:
					receiveSnapshot(message.getPayload());
					break;
				case ACTION:
					receiveAction(message.getPayload());
					break;
				case ACTION_ACK:
					receiveAck(message.getPayload());
					break;
				case CONCEDE:
					receiveTransportConcede(message.getPayload());
					break;
				default:
					fail(false, "Match synchronization failed");
			}
		}
		catch (RuntimeException exception)
		{
			fail(state == null, state == null ? "Opponent setup was rejected"
				: "Match synchronization failed");
		}
	}

	public void accept(PartyApplicationMessage message)
	{
		receive(message);
	}

	public void onApplicationMessage(PartyApplicationMessage message)
	{
		receive(message);
	}

	public void abort()
	{
		if (status == PartyMatchSnapshot.Status.COMPLETE || status == PartyMatchSnapshot.Status.ABORTED) return;
		status = PartyMatchSnapshot.Status.ABORTED;
		userStatus = "Match aborted";
		publish();
	}

	public PartyMatchSnapshot snapshot()
	{
		return new PartyMatchSnapshot(status, localSeat, peerSeat, revision, state, userStatus);
	}

	public void addListener(PartyMatchListener listener)
	{
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	public void removeListener(PartyMatchListener listener)
	{
		listeners.remove(listener);
	}

	private void validateLocalConfiguration()
	{
		if (catalog.getRulesetVersion() != GwentEngine.RULESET_VERSION
			|| duel.getRulesetVersion() != GwentEngine.RULESET_VERSION
			|| !Objects.equals(duel.getCatalogHash(), catalog.getSha256()))
			throw new IllegalArgumentException("duel catalog metadata does not match catalog");
		if (!localDeck.getId().equals(duel.getDeckId())) throw new IllegalArgumentException("selected deck ID does not match READY");
		if (!DeckCommitment.compute(catalog.getSha256(), catalog.getRulesetVersion(), localDeck)
			.equals(duel.getDeckCommitment())) throw new IllegalArgumentException("selected deck does not match READY commitment");
		if (!validator.validate(localDeck, cardLookup, Collections.emptySet(), OwnedCardCollectionStatus.UNKNOWN).isValid())
			throw new IllegalArgumentException("selected deck is invalid");
	}

	private void receiveSnapshot(Map<String, Object> payload)
	{
		String kind = string(payload == null ? null : payload.get("kind"), 1, 16, "kind");
		if ("SEED_COMMIT".equals(kind))
		{
			receiveSeedCommit(payload);
			return;
		}
		if ("SETUP".equals(kind))
		{
			receiveSetup(payload);
			return;
		}
		throw new IllegalArgumentException("invalid snapshot kind");
	}

	private void receiveSeedCommit(Map<String, Object> payload)
	{
		if (!started) throw new IllegalArgumentException("match is not started");
		requireKeys(payload, "commitment", "kind");
		String commitment = hash(payload.get("commitment"), "commitment");
		if (peerSeedCommitment != null)
		{
			if (!peerSeedCommitment.equals(commitment))
				throw new IllegalArgumentException("conflicting seed commitment");
			sendSilently(BattlePartyMessageType.SNAPSHOT, setupPayload(localDeck, localContribution));
			return;
		}
		peerSeedCommitment = commitment;
		setupRetries = 0;
		sendSilently(BattlePartyMessageType.SNAPSHOT, setupPayload(localDeck, localContribution));
		userStatus = "Waiting for opponent setup reveal";
		publish();
	}

	private void receiveSetup(Map<String, Object> payload)
	{
		if (peerSeedCommitment == null) return;
		Setup setup = parseSetup(payload);
		if (!peerSeedCommitment.equals(seedCommitment(peerMemberId, setup.contribution)))
			throw new IllegalArgumentException("seed reveal does not match commitment");
		if (peerSetup != null)
		{
			if (!peerSetup.equals(setup)) fail(false, "Match synchronization failed");
			return;
		}
		if (!setup.deck.getId().equals(duel.getPeerDeckId()))
			throw new IllegalArgumentException("peer deck ID mismatch");
		String commitment = DeckCommitment.compute(catalog.getSha256(), catalog.getRulesetVersion(), setup.deck);
		if (!commitment.equals(duel.getPeerDeckCommitment()))
			throw new IllegalArgumentException("peer deck commitment mismatch");
		if (!validator.validate(setup.deck, cardLookup, Collections.emptySet(), OwnedCardCollectionStatus.UNKNOWN).isValid())
			throw new IllegalArgumentException("peer deck is invalid");

		List<? extends com.osrstcgbattles.engine.Card> localCards = deckFactory.create(localDeck);
		List<? extends com.osrstcgbattles.engine.Card> peerCards = deckFactory.create(setup.deck);
		long seed = deriveSeed(duel.getMatchId(), localMemberId, localContribution, peerMemberId, setup.contribution);
		state = localSeat == PlayerId.PLAYER_ONE ? engine.newMatchWithMulligan(localCards, peerCards, seed)
			: engine.newMatchWithMulligan(peerCards, localCards, seed);
		peerSetup = setup;
		setupRetries = 0;
		status = PartyMatchSnapshot.Status.ACTIVE;
		userStatus = "Match active";
		publish();
	}

	private void receiveAction(Map<String, Object> payload)
	{
		requirePresent(payload, "actor", "command", "priorHash", "priorRevision", "resultingHash");
		long wireRevision = integer(payload.get("priorRevision"), 0, MAX_WIRE_INTEGER, "priorRevision");
		String priorHash = hash(payload.get("priorHash"), "priorHash");
		String resultingHash = hash(payload.get("resultingHash"), "resultingHash");
		String actor = string(payload.get("actor"), 1, 32, "actor");
		Map<String, Object> applied = appliedPeerActions.get(wireRevision);
		if (applied != null && applied.equals(payload))
		{
			sendAck(wireRevision + 1, resultingHash);
			return;
		}
		if (status != PartyMatchSnapshot.Status.ACTIVE) throw new IllegalArgumentException("match is not active");
		if (!peerSeat.name().equals(actor) || wireRevision != revision
			|| !state.getSynchronizationStateHash().equals(priorHash))
		{
			fail(false, "Match synchronization failed");
			return;
		}

		Command command = parseCommand(payload, peerSeat);
		CommandResult result = engine.execute(state, command);
		if (!result.isAccepted() || !result.getState().getSynchronizationStateHash().equals(resultingHash))
		{
			fail(false, "Match synchronization failed");
			return;
		}
		MatchState nextState = result.getState();
		long nextRevision = revision + 1;
		state = nextState;
		revision = nextRevision;
		appliedPeerActions.put(wireRevision, immutableCopy(payload));
		sendAck(nextRevision, nextState.getSynchronizationStateHash());
		updateStatusFromEngine();
		publish();
	}

	private void receiveAck(Map<String, Object> payload)
	{
		if (state == null || (status != PartyMatchSnapshot.Status.ACTIVE
			&& status != PartyMatchSnapshot.Status.COMPLETE)) throw new IllegalArgumentException("match is not initialized");
		requireKeys(payload, "hash", "revision");
		long acknowledged = integer(payload.get("revision"), 0, MAX_WIRE_INTEGER, "revision");
		String acknowledgedHash = hash(payload.get("hash"), "hash");
		if (pendingRevision == null)
		{
			if (acknowledgedHash.equals(acknowledgedLocalActions.get(acknowledged))) return;
			fail(false, "Match synchronization failed");
			return;
		}
		if (acknowledged != pendingRevision || !pendingHash.equals(acknowledgedHash))
		{
			fail(false, "Match synchronization failed");
			return;
		}
		acknowledgedLocalActions.put(acknowledged, acknowledgedHash);
		pendingRevision = null;
		pendingHash = null;
		pendingActionPayload = null;
	}

	private void receiveTransportConcede(Map<String, Object> payload)
	{
		if (status == PartyMatchSnapshot.Status.COMPLETE && payload.isEmpty()) return;
		if (status != PartyMatchSnapshot.Status.ACTIVE || !payload.isEmpty())
			throw new IllegalArgumentException("invalid transport concede");
		CommandResult result = engine.execute(state, new ConcedeCommand(peerSeat));
		if (!result.isAccepted()) throw new IllegalArgumentException("illegal concede");
		state = result.getState();
		revision++;
		status = PartyMatchSnapshot.Status.COMPLETE;
		userStatus = "Match complete";
		publish();
	}

	private Command parseCommand(Map<String, Object> payload, PlayerId actor)
	{
		String type = string(payload.get("command"), 1, 16, "command");
		switch (type)
		{
			case "END_TURN":
				requireKeys(payload, "actor", "command", "priorHash", "priorRevision", "resultingHash");
				return new EndTurnCommand(actor);
			case "CONCEDE":
				requireKeys(payload, "actor", "command", "priorHash", "priorRevision", "resultingHash");
				return new ConcedeCommand(actor);
			case "KEEP":
			case "FINISH_MULLIGAN":
				requireKeys(payload, "actor", "command", "priorHash", "priorRevision", "resultingHash");
				return new FinishMulliganCommand(actor);
			case "MULLIGAN":
				requireKeys(payload, "actor", "cardId", "command", "priorHash", "priorRevision", "resultingHash");
				return new MulliganCommand(actor, string(payload.get("cardId"), 1, MAX_ID_LENGTH, "cardId"));
			case "PLAY":
				Set<String> allowed = new HashSet<>();
				Collections.addAll(allowed, "actor", "command", "priorHash", "priorRevision", "resultingHash",
					"cardId", "target");
				if (!allowed.containsAll(payload.keySet()) || !payload.keySet().containsAll(
					java.util.Arrays.asList("actor", "command", "priorHash", "priorRevision", "resultingHash", "cardId")))
					throw new IllegalArgumentException("invalid PLAY fields");
				String cardId = string(payload.get("cardId"), 1, MAX_ID_LENGTH, "cardId");
				String target = payload.containsKey("target")
					? string(payload.get("target"), 1, MAX_ID_LENGTH, "target") : null;
				return new PlayCardCommand(actor, cardId, target);
			case "ATTACK":
				Set<String> attackAllowed = new HashSet<>();
				Collections.addAll(attackAllowed, "actor", "command", "priorHash", "priorRevision", "resultingHash",
					"attacker", "target");
				if (!attackAllowed.containsAll(payload.keySet()) || !payload.keySet().containsAll(
					java.util.Arrays.asList("actor", "command", "priorHash", "priorRevision", "resultingHash", "attacker")))
					throw new IllegalArgumentException("invalid ATTACK fields");
				String attacker = string(payload.get("attacker"), 1, MAX_ID_LENGTH, "attacker");
				String attackTarget = payload.containsKey("target")
					? string(payload.get("target"), 1, MAX_ID_LENGTH, "target") : null;
				return new AttackCommand(actor, attacker, attackTarget);
			default:
				throw new IllegalArgumentException("unknown command");
		}
	}

	private Map<String, Object> actionPayload(Command command, long priorRevision, String priorHash, String resultHash)
	{
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("actor", command.getPlayer().name());
		payload.put("priorRevision", priorRevision);
		payload.put("priorHash", priorHash);
		payload.put("resultingHash", resultHash);
		if (command instanceof PlayCardCommand)
		{
			PlayCardCommand play = (PlayCardCommand) command;
			payload.put("command", "PLAY");
			payload.put("cardId", play.getCardId());
			play.getTargetInstanceId().ifPresent(target -> payload.put("target", target));
		}
		else if (command instanceof AttackCommand)
		{
			AttackCommand attack = (AttackCommand) command;
			payload.put("command", "ATTACK");
			payload.put("attacker", attack.getAttackerInstanceId());
			attack.getTargetInstanceId().ifPresent(target -> payload.put("target", target));
		}
		else if (command instanceof EndTurnCommand) payload.put("command", "END_TURN");
		else if (command instanceof ConcedeCommand) payload.put("command", "CONCEDE");
		else if (command instanceof MulliganCommand)
		{
			payload.put("command", "MULLIGAN");
			payload.put("cardId", ((MulliganCommand) command).getCardId());
		}
		else if (command instanceof FinishMulliganCommand) payload.put("command", "FINISH_MULLIGAN");
		else throw new IllegalArgumentException("unsupported command");
		return Collections.unmodifiableMap(payload);
	}

	private Map<String, Object> ackPayload(long acknowledgedRevision, String acknowledgedHash)
	{
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("revision", acknowledgedRevision);
		payload.put("hash", acknowledgedHash);
		return Collections.unmodifiableMap(payload);
	}

	private void sendAck(long acknowledgedRevision, String acknowledgedHash)
	{
		lastAckPayload = ackPayload(acknowledgedRevision, acknowledgedHash);
		ackRetries = 0;
		sendSilently(BattlePartyMessageType.ACTION_ACK, lastAckPayload);
	}

	private Map<String, Object> setupPayload(Deck deck, long contribution)
	{
		List<Map<String, Object>> cards = new ArrayList<>();
		for (DeckEntry entry : deck.getEntries())
		{
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("cardId", entry.getCardId());
			card.put("count", entry.getCount());
			cards.add(Collections.unmodifiableMap(card));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", "SETUP");
		payload.put("deckId", deck.getId());
		payload.put("cards", Collections.unmodifiableList(cards));
		payload.put("seedContribution", String.format("%016x", contribution));
		return Collections.unmodifiableMap(payload);
	}

	private Map<String, Object> seedCommitPayload()
	{
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", "SEED_COMMIT");
		payload.put("commitment", seedCommitment(localMemberId, localContribution));
		return Collections.unmodifiableMap(payload);
	}

	private Setup parseSetup(Map<String, Object> payload)
	{
		requireKeys(payload, "cards", "deckId", "kind", "seedContribution");
		if (!"SETUP".equals(string(payload.get("kind"), 1, 16, "kind")))
			throw new IllegalArgumentException("invalid snapshot kind");
		String deckId = string(payload.get("deckId"), 1, MAX_ID_LENGTH, "deckId");
		String seed = string(payload.get("seedContribution"), 16, 16, "seedContribution");
		if (!seed.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("invalid seed contribution");
		Object cardsValue = payload.get("cards");
		if (!(cardsValue instanceof List)) throw new IllegalArgumentException("cards must be a list");
		List<?> cards = (List<?>) cardsValue;
		if (cards.isEmpty() || cards.size() > DeckValidator.MAX_CARDS)
			throw new IllegalArgumentException("invalid card entry count");
		List<DeckEntry> entries = new ArrayList<>();
		String previous = null;
		for (Object value : cards)
		{
			if (!(value instanceof Map)) throw new IllegalArgumentException("card entry must be an object");
			@SuppressWarnings("unchecked") Map<String, Object> card = (Map<String, Object>) value;
			requireKeys(card, "cardId", "count");
			String cardId = string(card.get("cardId"), 1, MAX_ID_LENGTH, "cardId");
			if (previous != null && previous.compareTo(cardId) >= 0)
				throw new IllegalArgumentException("card entries are not canonical");
			int count = (int) integer(card.get("count"), 1, DeckValidator.MAX_CARDS, "count");
			entries.add(new DeckEntry(cardId, count));
			previous = cardId;
		}
		return new Setup(new Deck(deckId, "Peer deck", entries), Long.parseUnsignedLong(seed, 16));
	}

	private void updateStatusFromEngine()
	{
		if (state.getStatus() == MatchStatus.COMPLETE)
		{
			status = PartyMatchSnapshot.Status.COMPLETE;
			userStatus = "Match complete";
		}
		else
		{
			status = PartyMatchSnapshot.Status.ACTIVE;
			userStatus = "Match active";
		}
	}

	private void fail(boolean setupFailure, String safeStatus)
	{
		if (status == PartyMatchSnapshot.Status.COMPLETE || status == PartyMatchSnapshot.Status.DESYNC
			|| status == PartyMatchSnapshot.Status.ABORTED) return;
		status = setupFailure ? PartyMatchSnapshot.Status.ABORTED : PartyMatchSnapshot.Status.DESYNC;
		userStatus = safeStatus;
		publish();
	}

	private void timeout()
	{
		if (status == PartyMatchSnapshot.Status.COMPLETE) return;
		status = PartyMatchSnapshot.Status.ABORTED;
		userStatus = "Match synchronization timed out";
		publish();
	}

	private boolean sendSilently(BattlePartyMessageType type, Map<String, ?> payload)
	{
		try
		{
			return channel.send(type, payload);
		}
		catch (RuntimeException exception)
		{
			return false;
		}
	}

	private static Map<String, Object> immutableCopy(Map<String, Object> payload)
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}

	private void publish()
	{
		PartyMatchSnapshot current = snapshot();
		for (PartyMatchListener listener : new ArrayList<>(listeners)) listener.onPartyMatchSnapshotChanged(current);
	}

	private static Deck canonicalDeck(Deck deck)
	{
		List<DeckEntry> entries = new ArrayList<>(deck.getEntries());
		entries.sort(Comparator.comparing(DeckEntry::getCardId));
		return new Deck(deck.getId(), deck.getName(), entries);
	}

	private static long deriveSeed(String matchId, long firstMember, long firstContribution,
		long secondMember, long secondContribution)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateBytes(digest, SEED_DOMAIN);
			updateBytes(digest, matchId.getBytes(StandardCharsets.UTF_8));
			if (firstMember < secondMember)
			{
				updatePair(digest, firstMember, firstContribution);
				updatePair(digest, secondMember, secondContribution);
			}
			else
			{
				updatePair(digest, secondMember, secondContribution);
				updatePair(digest, firstMember, firstContribution);
			}
			return ByteBuffer.wrap(digest.digest()).getLong();
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String seedCommitment(long memberId, long contribution)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateBytes(digest, SEED_COMMITMENT_DOMAIN);
			updateBytes(digest, duel.getMatchId().getBytes(StandardCharsets.UTF_8));
			digest.update(ByteBuffer.allocate(Long.BYTES * 2).putLong(memberId).putLong(contribution).array());
			return hex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String hex(byte[] bytes)
	{
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
		{
			result.append(Character.forDigit((value >>> 4) & 0xf, 16));
			result.append(Character.forDigit(value & 0xf, 16));
		}
		return result.toString();
	}

	private static void updateBytes(MessageDigest digest, byte[] value)
	{
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
		digest.update(value);
	}

	private static void updatePair(MessageDigest digest, long member, long contribution)
	{
		digest.update(ByteBuffer.allocate(Long.BYTES * 2).putLong(member).putLong(contribution).array());
	}

	private static void requireKeys(Map<String, ?> map, String... keys)
	{
		if (map == null || map.size() != keys.length) throw new IllegalArgumentException("invalid payload fields");
		for (String key : keys) if (!map.containsKey(key)) throw new IllegalArgumentException("missing payload field");
	}

	private static void requirePresent(Map<String, ?> map, String... keys)
	{
		if (map == null) throw new IllegalArgumentException("payload is required");
		for (String key : keys) if (!map.containsKey(key)) throw new IllegalArgumentException("missing payload field");
	}

	private static String string(Object value, int min, int max, String name)
	{
		if (!(value instanceof String)) throw new IllegalArgumentException(name + " must be a string");
		String text = (String) value;
		if (text.length() < min || text.length() > max) throw new IllegalArgumentException(name + " is out of bounds");
		return text;
	}

	private static String hash(Object value, String name)
	{
		String text = string(value, 64, 64, name);
		if (!text.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " is invalid");
		return text;
	}

	private static long integer(Object value, long min, long max, String name)
	{
		if (!(value instanceof Number)) throw new IllegalArgumentException(name + " must be an integer");
		double number = ((Number) value).doubleValue();
		if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max)
			throw new IllegalArgumentException(name + " is out of bounds");
		return (long) number;
	}

	private static final class Setup
	{
		private final Deck deck;
		private final long contribution;

		private Setup(Deck deck, long contribution)
		{
			this.deck = deck;
			this.contribution = contribution;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Setup)) return false;
			Setup that = (Setup) other;
			return contribution == that.contribution && deck.getId().equals(that.deck.getId())
				&& deck.getEntries().equals(that.deck.getEntries());
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(deck.getId(), deck.getEntries(), contribution);
		}
	}
}
