package com.osrstcgbattles.collection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** An immutable, atomically published view of owned card names. */
public final class OwnedCardCollectionSnapshot
{
	private final OwnedCardCollectionStatus status;
	private final long revision;
	private final Map<String, String> displayNameByNormalizedName;
	private final Set<String> ownedNames;

	static OwnedCardCollectionSnapshot unknown(long revision)
	{
		return new OwnedCardCollectionSnapshot(
			OwnedCardCollectionStatus.UNKNOWN, revision, Collections.emptyMap());
	}

	static OwnedCardCollectionSnapshot known(long revision, Map<String, String> names)
	{
		return new OwnedCardCollectionSnapshot(OwnedCardCollectionStatus.KNOWN, revision, names);
	}

	private OwnedCardCollectionSnapshot(
		OwnedCardCollectionStatus status,
		long revision,
		Map<String, String> displayNameByNormalizedName)
	{
		this.status = status;
		this.revision = revision;
		this.displayNameByNormalizedName = Collections.unmodifiableMap(
			new LinkedHashMap<>(displayNameByNormalizedName));
		this.ownedNames = Collections.unmodifiableSet(
			new LinkedHashSet<>(this.displayNameByNormalizedName.values()));
	}

	public OwnedCardCollectionStatus getStatus()
	{
		return status;
	}

	public boolean isKnown()
	{
		return status == OwnedCardCollectionStatus.KNOWN;
	}

	public long getRevision()
	{
		return revision;
	}

	/** Trimmed display names, preserving the first spelling in the payload. */
	public Set<String> getOwnedNames()
	{
		return ownedNames;
	}

	/** Lower-case {@link Locale#ROOT} names suitable for joins and lookups. */
	public Set<String> getNormalizedOwnedNames()
	{
		return displayNameByNormalizedName.keySet();
	}

	public boolean owns(String cardName)
	{
		if (!isKnown() || cardName == null)
		{
			return false;
		}
		String normalized = cardName.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty())
		{
			return false;
		}
		if (displayNameByNormalizedName.containsKey(normalized))
		{
			return true;
		}
		String canonicalName = v1CanonicalName(normalized);
		return canonicalName != null && displayNameByNormalizedName.containsKey(canonicalName);
	}

	private static String v1CanonicalName(String legacyName)
	{
		// V1 folds NPC variants into parent cards and disambiguates item/NPC name collisions.
		switch (legacyName)
		{
			case "ice troll male":
			case "frenzied ice troll male":
				return "ice troll";
			case "scorpia's offspring":
				return "npc:6616";
			default:
				return null;
		}
	}
}
