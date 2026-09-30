package com.osrstcgbattles.backend.ranked;

import com.osrstcgbattles.backend.rating.Glicko2Rating;
import com.osrstcgbattles.backend.rating.Outcome;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An immutable completed-match audit record. */
public record RankedMatch(
	String matchId,
	UUID playerOneId,
	UUID playerTwoId,
	Outcome playerOneOutcome,
	Instant completedAt,
	Glicko2Rating playerOneBefore,
	Glicko2Rating playerOneAfter,
	Glicko2Rating playerTwoBefore,
	Glicko2Rating playerTwoAfter)
{
	public RankedMatch
	{
		if (Objects.requireNonNull(matchId, "matchId").isBlank())
		{
			throw new IllegalArgumentException("matchId cannot be blank");
		}
		Objects.requireNonNull(playerOneId, "playerOneId");
		Objects.requireNonNull(playerTwoId, "playerTwoId");
		Objects.requireNonNull(playerOneOutcome, "playerOneOutcome");
		Objects.requireNonNull(completedAt, "completedAt");
		Objects.requireNonNull(playerOneBefore, "playerOneBefore");
		Objects.requireNonNull(playerOneAfter, "playerOneAfter");
		Objects.requireNonNull(playerTwoBefore, "playerTwoBefore");
		Objects.requireNonNull(playerTwoAfter, "playerTwoAfter");
	}
}
