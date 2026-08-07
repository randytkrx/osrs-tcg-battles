package com.osrstcgbattles.persist;

import java.util.Objects;
import java.util.Optional;
import net.runelite.client.config.ConfigManager;

/** Stores deck state in the active RuneLite configuration profile. */
public final class DeckProfileRepository
{
	public static final String CONFIG_GROUP = "osrsTcgBattlesDecks";
	public static final String CONFIG_KEY = "profileJson";

	private final ConfigManager configManager;
	private final DeckProfileCodec codec;
	private DeckProfile current = DeckProfile.empty();

	public DeckProfileRepository(ConfigManager configManager, DeckProfileCodec codec)
	{
		this.configManager = Objects.requireNonNull(configManager, "configManager");
		this.codec = Objects.requireNonNull(codec, "codec");
	}

	public synchronized DeckProfile getCurrent()
	{
		return current;
	}

	/**
	 * Loads the active profile. Invalid JSON is ignored, leaving the last valid state untouched.
	 *
	 * @return true when stored data was present and successfully decoded
	 */
	public synchronized boolean reload()
	{
		String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY);
		Optional<DeckProfile> decoded = codec.decode(json);
		if (!decoded.isPresent())
		{
			return false;
		}
		current = decoded.get();
		return true;
	}

	public synchronized void save(DeckProfile profile)
	{
		Objects.requireNonNull(profile, "profile");
		String json = codec.encode(profile);
		configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, json);
		current = profile;
	}
}
