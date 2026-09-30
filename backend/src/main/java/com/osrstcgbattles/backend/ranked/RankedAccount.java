package com.osrstcgbattles.backend.ranked;

import com.osrstcgbattles.backend.rating.Glicko2Rating;
import java.util.Objects;
import java.util.UUID;

/** A ranked identity and its current rating. */
public record RankedAccount(
	UUID accountId,
	String displayName,
	String normalizedIgn,
	byte[] publicKey,
	Glicko2Rating rating)
{
	public RankedAccount
	{
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(normalizedIgn, "normalizedIgn");
		publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
		Objects.requireNonNull(rating, "rating");
	}

	@Override
	public byte[] publicKey()
	{
		return publicKey.clone();
	}
}
