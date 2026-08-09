package com.osrstcgbattles.art;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Card name to picture source. Deliberately outside the hashed battle catalog. */
public final class CardArtCatalog
{
	static final String RESOURCE = "/com/osrstcgbattles/card-art.tsv";

	private final Map<String, CardArtSource> byName;

	public CardArtCatalog()
	{
		Map<String, CardArtSource> loaded = new LinkedHashMap<>();
		try (InputStream in = CardArtCatalog.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("Card art resource not found: " + RESOURCE);
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#"))
				{
					continue;
				}
				String[] fields = line.split("\t", -1);
				if (fields.length < 2 || fields.length > 3)
				{
					throw new IllegalStateException("Malformed card art row: " + line);
				}
				CardArtSource.Kind kind = CardArtSource.Kind.valueOf(fields[1].trim());
				loaded.put(key(fields[0]), new CardArtSource(kind,
					fields.length == 3 ? fields[2].trim() : ""));
			}
		}
		catch (IOException ex)
		{
			throw new UncheckedIOException("Could not read " + RESOURCE, ex);
		}
		byName = Collections.unmodifiableMap(loaded);
	}

	public Optional<CardArtSource> lookup(String azCardName)
	{
		return azCardName == null ? Optional.empty() : Optional.ofNullable(byName.get(key(azCardName)));
	}

	private static String key(String value)
	{
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
