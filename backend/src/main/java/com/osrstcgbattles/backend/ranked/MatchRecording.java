package com.osrstcgbattles.backend.ranked;

import java.util.Objects;

/** The result of an idempotent completed-match write. */
public record MatchRecording(RankedMatch match, boolean newlyRecorded)
{
	public MatchRecording
	{
		Objects.requireNonNull(match, "match");
	}
}
