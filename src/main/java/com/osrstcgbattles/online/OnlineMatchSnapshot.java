package com.osrstcgbattles.online;

import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.match.MatchView;
import java.util.Objects;

public final class OnlineMatchSnapshot
{
	public enum Status { WAITING_STATE, ACTIVE, COMPLETE, ERROR }

	private final Status status;
	private final String matchId;
	private final PlayerId localSeat;
	private final String opponentDisplayName;
	private final long revision;
	private final MatchView view;
	private final String message;

	OnlineMatchSnapshot(Status status, String matchId, PlayerId localSeat, String opponentDisplayName,
		long revision, MatchView view, String message)
	{
		this.status = Objects.requireNonNull(status, "status");
		this.matchId = Objects.requireNonNull(matchId, "matchId");
		this.localSeat = Objects.requireNonNull(localSeat, "localSeat");
		this.opponentDisplayName = Objects.requireNonNull(opponentDisplayName, "opponentDisplayName");
		this.revision = revision;
		this.view = view;
		this.message = Objects.requireNonNull(message, "message");
	}

	public Status getStatus() { return status; }
	public String getMatchId() { return matchId; }
	public PlayerId getLocalSeat() { return localSeat; }
	public String getOpponentDisplayName() { return opponentDisplayName; }
	public long getRevision() { return revision; }
	public MatchView getView() { return view; }
	public String getMessage() { return message; }
}
