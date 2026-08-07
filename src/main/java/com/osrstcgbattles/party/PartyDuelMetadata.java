package com.osrstcgbattles.party;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated metadata exchanged only inside the encrypted READY message. */
public final class PartyDuelMetadata
{
	private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
	private final String catalogHash;
	private final int rulesetVersion;
	private final String deckId;
	private final String deckCommitment;

	public PartyDuelMetadata(String catalogHash, int rulesetVersion, String deckId, String deckCommitment)
	{
		if (!isSha256(catalogHash) || rulesetVersion <= 0 || rulesetVersion > 1_000_000
			|| !isBoundedId(deckId) || !isSha256(deckCommitment))
		{
			throw new IllegalArgumentException("invalid duel metadata");
		}
		this.catalogHash = catalogHash.toLowerCase(java.util.Locale.ROOT);
		this.rulesetVersion = rulesetVersion;
		this.deckId = Objects.requireNonNull(deckId, "deckId");
		this.deckCommitment = deckCommitment.toLowerCase(java.util.Locale.ROOT);
	}

	private static boolean isSha256(String value) { return value != null && SHA256.matcher(value).matches(); }
	private static boolean isBoundedId(String value)
	{
		if (value == null || value.isEmpty() || value.length() > BattlePartyEnvelope.MAX_ID_LENGTH) return false;
		for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e) return false;
		return true;
	}

	public String getCatalogHash() { return catalogHash; }
	public int getRulesetVersion() { return rulesetVersion; }
	public String getDeckId() { return deckId; }
	public String getDeckCommitment() { return deckCommitment; }
}
