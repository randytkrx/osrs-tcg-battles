package com.osrstcgbattles.match;

import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayerId;
import java.util.Objects;
import java.util.Optional;

/** Immutable view of a synchronized party match. */
public final class PartyMatchSnapshot
{
	public enum Status
	{
		WAITING_SETUP, ACTIVE, COMPLETE, DESYNC, ABORTED
	}

	private final Status status;
	private final PlayerId localSeat;
	private final PlayerId peerSeat;
	private final long revision;
	private final MatchState matchState;
	private final String userStatus;

	PartyMatchSnapshot(Status status, PlayerId localSeat, PlayerId peerSeat, long revision,
		MatchState matchState, String userStatus)
	{
		this.status = Objects.requireNonNull(status, "status");
		this.localSeat = Objects.requireNonNull(localSeat, "localSeat");
		this.peerSeat = Objects.requireNonNull(peerSeat, "peerSeat");
		this.revision = revision;
		this.matchState = matchState;
		this.userStatus = Objects.requireNonNull(userStatus, "userStatus");
	}

	public Status getStatus() { return status; }
	public PlayerId getLocalSeat() { return localSeat; }
	public PlayerId getPeerSeat() { return peerSeat; }
	public long getRevision() { return revision; }
	public MatchState getMatchState() { return matchState; }
	public Optional<MatchState> getInitializedMatchState() { return Optional.ofNullable(matchState); }
	public String getUserStatus() { return userStatus; }

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof PartyMatchSnapshot)) return false;
		PartyMatchSnapshot that = (PartyMatchSnapshot) other;
		return status == that.status && localSeat == that.localSeat && peerSeat == that.peerSeat
			&& revision == that.revision && Objects.equals(matchState, that.matchState)
			&& userStatus.equals(that.userStatus);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(status, localSeat, peerSeat, revision, matchState, userStatus);
	}
}
