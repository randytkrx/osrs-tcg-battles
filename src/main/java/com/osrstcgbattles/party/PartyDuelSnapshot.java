package com.osrstcgbattles.party;

import java.util.Objects;

/** Immutable, secret-free view of the current party transport session. */
public final class PartyDuelSnapshot
{
	public enum Status
	{
		IDLE, OUTBOUND_INVITE, INBOUND_INVITE, HANDSHAKE, READY, TERMINAL
	}

	private static final PartyDuelSnapshot IDLE = new PartyDuelSnapshot(Status.IDLE, null, 0, null,
		"No party duel in progress", false, false, false, null, 0, null, null);

	private final Status status;
	private final String matchId;
	private final long peerMemberId;
	private final String peerDisplayName;
	private final String userStatus;
	private final boolean canAccept;
	private final boolean canDecline;
	private final boolean canAbort;
	private final String catalogHash;
	private final int rulesetVersion;
	private final String deckId;
	private final String deckCommitment;
	private final String peerDeckId;
	private final String peerDeckCommitment;

	PartyDuelSnapshot(Status status, String matchId, long peerMemberId, String peerDisplayName,
		String userStatus, boolean canAccept, boolean canDecline, boolean canAbort, String catalogHash,
		int rulesetVersion, String deckId, String deckCommitment)
	{
		this(status, matchId, peerMemberId, peerDisplayName, userStatus, canAccept, canDecline, canAbort,
			catalogHash, rulesetVersion, deckId, deckCommitment, null, null);
	}

	PartyDuelSnapshot(Status status, String matchId, long peerMemberId, String peerDisplayName,
		String userStatus, boolean canAccept, boolean canDecline, boolean canAbort, String catalogHash,
		int rulesetVersion, String deckId, String deckCommitment, String peerDeckId, String peerDeckCommitment)
	{
		this.status = Objects.requireNonNull(status, "status");
		this.matchId = matchId;
		this.peerMemberId = peerMemberId;
		this.peerDisplayName = peerDisplayName;
		this.userStatus = Objects.requireNonNull(userStatus, "userStatus");
		this.canAccept = canAccept;
		this.canDecline = canDecline;
		this.canAbort = canAbort;
		this.catalogHash = catalogHash;
		this.rulesetVersion = rulesetVersion;
		this.deckId = deckId;
		this.deckCommitment = deckCommitment;
		this.peerDeckId = peerDeckId;
		this.peerDeckCommitment = peerDeckCommitment;
	}

	public static PartyDuelSnapshot idle() { return IDLE; }
	public Status getStatus() { return status; }
	public String getMatchId() { return matchId; }
	public long getPeerMemberId() { return peerMemberId; }
	public String getPeerDisplayName() { return peerDisplayName; }
	public String getUserStatus() { return userStatus; }
	public boolean canAccept() { return canAccept; }
	public boolean canDecline() { return canDecline; }
	public boolean canAbort() { return canAbort; }
	public boolean isAcceptAvailable() { return canAccept; }
	public boolean isDeclineAvailable() { return canDecline; }
	public boolean isAbortAvailable() { return canAbort; }
	public String getCatalogHash() { return catalogHash; }
	public int getRulesetVersion() { return rulesetVersion; }
	public String getDeckId() { return deckId; }
	public String getDeckCommitment() { return deckCommitment; }
	public String getPeerDeckId() { return peerDeckId; }
	public String getPeerDeckCommitment() { return peerDeckCommitment; }

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof PartyDuelSnapshot)) return false;
		PartyDuelSnapshot that = (PartyDuelSnapshot) other;
		return status == that.status && peerMemberId == that.peerMemberId && rulesetVersion == that.rulesetVersion
			&& canAccept == that.canAccept && canDecline == that.canDecline && canAbort == that.canAbort
			&& Objects.equals(matchId, that.matchId) && Objects.equals(peerDisplayName, that.peerDisplayName)
			&& userStatus.equals(that.userStatus) && Objects.equals(catalogHash, that.catalogHash)
			&& Objects.equals(deckId, that.deckId) && Objects.equals(deckCommitment, that.deckCommitment)
			&& Objects.equals(peerDeckId, that.peerDeckId)
			&& Objects.equals(peerDeckCommitment, that.peerDeckCommitment);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(status, matchId, peerMemberId, peerDisplayName, userStatus, canAccept, canDecline,
			canAbort, catalogHash, rulesetVersion, deckId, deckCommitment, peerDeckId, peerDeckCommitment);
	}
}
