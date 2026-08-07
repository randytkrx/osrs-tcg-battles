package com.osrstcgbattles.engine;

import java.util.Objects;
import java.util.Optional;

public final class CommandResult
{
	private final MatchState state;
	private final RejectionReason rejectionReason;

	private CommandResult(MatchState state, RejectionReason rejectionReason)
	{
		this.state = Objects.requireNonNull(state, "state");
		this.rejectionReason = rejectionReason;
	}

	static CommandResult accepted(MatchState state)
	{
		return new CommandResult(state, null);
	}

	static CommandResult rejected(MatchState state, RejectionReason reason)
	{
		return new CommandResult(state, Objects.requireNonNull(reason, "reason"));
	}

	public boolean isAccepted()
	{
		return rejectionReason == null;
	}

	public MatchState getState()
	{
		return state;
	}

	public Optional<RejectionReason> getRejectionReason()
	{
		return Optional.ofNullable(rejectionReason);
	}
}
