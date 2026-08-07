package com.osrstcgbattles.party;

import com.osrstcgbattles.deck.Deck;
import com.osrstcgbattles.deck.DeckEntry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Creates the public, deterministic commitment used by the party duel handshake. */
public final class DeckCommitment
{
	private static final byte[] DOMAIN = "OSRS-TCG-DECK-COMMITMENT-V1".getBytes(StandardCharsets.US_ASCII);

	private DeckCommitment()
	{
	}

	public static String compute(String catalogHash, int rulesetVersion, Deck deck)
	{
		Objects.requireNonNull(catalogHash, "catalogHash");
		Objects.requireNonNull(deck, "deck");
		if (rulesetVersion <= 0)
		{
			throw new IllegalArgumentException("rulesetVersion must be positive");
		}

		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateBytes(digest, DOMAIN);
			updateString(digest, catalogHash);
			updateInt(digest, rulesetVersion);
			updateString(digest, deck.getId());
			List<DeckEntry> entries = new ArrayList<>(deck.getEntries());
			entries.sort(Comparator.comparing(DeckEntry::getCardId).thenComparingInt(DeckEntry::getCount));
			updateInt(digest, entries.size());
			for (DeckEntry entry : entries)
			{
				updateString(digest, entry.getCardId());
				updateInt(digest, entry.getCount());
			}
			return toHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateString(MessageDigest digest, String value)
	{
		updateBytes(digest, Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
	}

	private static void updateBytes(MessageDigest digest, byte[] value)
	{
		updateInt(digest, value.length);
		digest.update(value);
	}

	private static void updateInt(MessageDigest digest, int value)
	{
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
	}

	private static String toHex(byte[] bytes)
	{
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
		{
			result.append(Character.forDigit((value >>> 4) & 0xf, 16));
			result.append(Character.forDigit(value & 0xf, 16));
		}
		return result.toString();
	}
}
