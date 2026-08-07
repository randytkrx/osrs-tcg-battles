package com.osrstcgbattles.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class BattleCardCatalogLoader
{
	public static final int SUPPORTED_CATALOG_VERSION = 2;
	public static final int SUPPORTED_RULESET_VERSION = 2;
	public static final String DEFAULT_RESOURCE = "/com/osrstcgbattles/battle-cards.json";

	private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Set<String> EFFECT_PARAM_KEYS = Collections.singleton("amount");
	private static final Set<String> TARGET_PARAM_KEYS = Collections.singleton("target");
	private static final Set<String> BOOST_TARGETS = new HashSet<>(Arrays.asList("SELF", "ALLIED_UNIT"));
	private static final Set<String> DAMAGE_TARGETS = Collections.singleton("ENEMY_UNIT");

	private final Gson gson;

	public BattleCardCatalogLoader()
	{
		this(new Gson());
	}

	BattleCardCatalogLoader(Gson gson)
	{
		this.gson = gson;
	}

	public BattleCardCatalog loadDefault()
	{
		InputStream stream = BattleCardCatalogLoader.class.getResourceAsStream(DEFAULT_RESOURCE);
		if (stream == null)
		{
			throw new CatalogValidationException("Catalog resource not found: " + DEFAULT_RESOURCE);
		}
		try (InputStream input = stream)
		{
			return load(input, DEFAULT_RESOURCE);
		}
		catch (IOException ex)
		{
			throw new CatalogValidationException("Could not close catalog resource: " + DEFAULT_RESOURCE, ex);
		}
	}

	public BattleCardCatalog load(Reader reader)
	{
		if (reader == null)
		{
			throw new CatalogValidationException("Catalog reader must not be null");
		}
		StringBuilder json = new StringBuilder();
		char[] buffer = new char[4096];
		try
		{
			int read;
			while ((read = reader.read(buffer)) != -1)
			{
				json.append(buffer, 0, read);
			}
		}
		catch (IOException ex)
		{
			throw new CatalogValidationException("Could not read catalog", ex);
		}
		byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
		return load(new ByteArrayInputStream(bytes), "reader");
	}

	public BattleCardCatalog load(InputStream input, String sourceName)
	{
		if (input == null)
		{
			throw new CatalogValidationException("Catalog input must not be null");
		}
		String source = isBlank(sourceName) ? "catalog" : sourceName;
		byte[] bytes = readAll(input, source);
		CatalogDto dto;
		try
		{
			dto = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), CatalogDto.class);
		}
		catch (JsonParseException ex)
		{
			throw new CatalogValidationException("Malformed JSON in " + source + ": " + ex.getMessage(), ex);
		}
		return validateAndBuild(dto, sha256(bytes), source);
	}

	private static byte[] readAll(InputStream input, String source)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		try
		{
			int read;
			while ((read = input.read(buffer)) != -1)
			{
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
		catch (IOException ex)
		{
			throw new CatalogValidationException("Could not read " + source, ex);
		}
	}

	private static BattleCardCatalog validateAndBuild(CatalogDto dto, String hash, String source)
	{
		if (dto == null)
		{
			throw invalid(source, "catalog root is null");
		}
		if (dto.catalogVersion != SUPPORTED_CATALOG_VERSION)
		{
			throw invalid(source, "unsupported catalogVersion " + dto.catalogVersion);
		}
		if (dto.rulesetVersion != SUPPORTED_RULESET_VERSION)
		{
			throw invalid(source, "unsupported rulesetVersion " + dto.rulesetVersion);
		}
		if (dto.cards == null || dto.cards.isEmpty())
		{
			throw invalid(source, "cards must not be empty");
		}

		Set<String> ids = new HashSet<>();
		Set<String> azNames = new HashSet<>();
		List<BattleCard> cards = new ArrayList<>();
		for (int i = 0; i < dto.cards.size(); i++)
		{
			CardDto card = dto.cards.get(i);
			String path = "cards[" + i + "]";
			if (card == null)
			{
				throw invalid(source, path + " is null");
			}
			requireText(card.id, source, path + ".id");
			if (!ID_PATTERN.matcher(card.id).matches())
			{
				throw invalid(source, path + ".id must be a stable kebab-case ID");
			}
			if (!ids.add(card.id))
			{
				throw invalid(source, "duplicate card id: " + card.id);
			}
			requireText(card.azCardName, source, path + ".azCardName");
			if (!azNames.add(card.azCardName))
			{
				throw invalid(source, "duplicate azCardName: " + card.azCardName);
			}
			requireText(card.displayName, source, path + ".displayName");
			requireEnum(card.category, source, path + ".category");
			requireEnum(card.faction, source, path + ".faction");
			requireEnum(card.rarity, source, path + ".rarity");
			if (card.manaCost == null || card.manaCost < 0 || card.manaCost > 10)
			{
				throw invalid(source, path + ".manaCost must be between 0 and 10");
			}
			if (card.attack == null || card.attack < 0 || card.attack > 20)
			{
				throw invalid(source, path + ".attack must be between 0 and 20");
			}
			if (card.health == null || card.health < 0 || card.health > 20)
			{
				throw invalid(source, path + ".health must be between 0 and 20");
			}
			if (card.category == CardCategory.UNIT && card.health == 0
				|| card.category == CardCategory.SPECIAL && (card.attack != 0 || card.health != 0))
			{
				throw invalid(source, path + " has invalid attack/health for " + card.category);
			}
			Set<String> tags = validateTags(card.tags, source, path);
			requireText(card.rulesText, source, path + ".rulesText");
			List<CardAbility> abilities = validateAbilities(card.abilities, card.category, source, path);
			cards.add(new BattleCard(card.id, card.azCardName, card.displayName, card.category, card.faction,
				card.rarity, card.manaCost, card.attack, card.health, tags, card.rulesText, abilities));
		}
		return new BattleCardCatalog(dto.catalogVersion, dto.rulesetVersion, hash, cards);
	}

	private static Set<String> validateTags(List<String> rawTags, String source, String path)
	{
		if (rawTags == null)
		{
			throw invalid(source, path + ".tags is required");
		}
		Set<String> tags = new LinkedHashSet<>();
		for (String tag : rawTags)
		{
			requireText(tag, source, path + ".tags[]");
			if (!tags.add(tag))
			{
				throw invalid(source, path + ".tags contains duplicate: " + tag);
			}
		}
		return tags;
	}

	private static List<CardAbility> validateAbilities(List<AbilityDto> raw, CardCategory category, String source,
		String path)
	{
		if (raw == null || raw.isEmpty())
		{
			throw invalid(source, path + ".abilities must not be empty");
		}
		List<CardAbility> abilities = new ArrayList<>();
		for (int i = 0; i < raw.size(); i++)
		{
			AbilityDto ability = raw.get(i);
			String abilityPath = path + ".abilities[" + i + "]";
			if (ability == null || ability.type == null)
			{
				throw invalid(source, abilityPath + ".type is missing or unknown");
			}
			Map<String, Integer> numeric = ability.numericParams == null
				? Collections.emptyMap() : ability.numericParams;
			Map<String, String> strings = ability.stringParams == null
				? Collections.emptyMap() : ability.stringParams;
			if (numeric.containsKey(null) || numeric.containsValue(null)
				|| strings.containsKey(null) || strings.containsValue(null))
			{
				throw invalid(source, abilityPath + " contains a null parameter");
			}
			if (ability.type == AbilityType.VANILLA)
			{
				if (!numeric.isEmpty() || !strings.isEmpty())
				{
					throw invalid(source, abilityPath + " VANILLA must not have parameters");
				}
			}
			else
			{
				validateEffectParams(ability.type, category, numeric, strings, source, abilityPath);
			}
			abilities.add(new CardAbility(ability.type, numeric, strings));
		}
		return abilities;
	}

	private static void validateEffectParams(AbilityType type, CardCategory category, Map<String, Integer> numeric,
		Map<String, String> strings, String source, String path)
	{
		if (!numeric.keySet().equals(EFFECT_PARAM_KEYS) || !strings.keySet().equals(TARGET_PARAM_KEYS))
		{
			throw invalid(source, path + " requires only numeric amount and string target parameters");
		}
		int amount = numeric.get("amount");
		if (amount < 1 || amount > 20)
		{
			throw invalid(source, path + ".numericParams.amount must be between 1 and 20");
		}
		String target = strings.get("target");
		Set<String> allowedTargets = type == AbilityType.DEPLOY_BOOST ? BOOST_TARGETS : DAMAGE_TARGETS;
		if (!allowedTargets.contains(target))
		{
			throw invalid(source, path + ".stringParams.target is unknown for " + type);
		}
		// Specials never enter the board, so they have no self to target.
		if (category == CardCategory.SPECIAL && "SELF".equals(target))
		{
			throw invalid(source, path + ".stringParams.target must not be SELF for a special");
		}
	}

	private static void requireText(String value, String source, String path)
	{
		if (isBlank(value))
		{
			throw invalid(source, path + " must not be blank");
		}
	}

	private static void requireEnum(Object value, String source, String path)
	{
		if (value == null)
		{
			throw invalid(source, path + " is missing or unknown");
		}
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}

	private static CatalogValidationException invalid(String source, String message)
	{
		return new CatalogValidationException("Invalid catalog " + source + ": " + message);
	}

	private static String sha256(byte[] bytes)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder result = new StringBuilder(64);
			for (byte value : digest)
			{
				result.append(String.format("%02x", value & 0xff));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static final class CatalogDto
	{
		private int catalogVersion;
		private int rulesetVersion;
		private List<CardDto> cards;
	}

	private static final class CardDto
	{
		private String id;
		private String azCardName;
		private String displayName;
		private CardCategory category;
		private Faction faction;
		private Rarity rarity;
		private Integer manaCost;
		private Integer attack;
		private Integer health;
		private List<String> tags;
		private String rulesText;
		private List<AbilityDto> abilities;
	}

	private static final class AbilityDto
	{
		private AbilityType type;
		private Map<String, Integer> numericParams = new LinkedHashMap<>();
		private Map<String, String> stringParams = new LinkedHashMap<>();
	}
}
