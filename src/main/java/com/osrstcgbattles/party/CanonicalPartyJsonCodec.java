package com.osrstcgbattles.party;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded canonical JSON for protocol DTOs; it never accepts a runtime type discriminator. */
public final class CanonicalPartyJsonCodec
{
	public static final int MAX_JSON_BYTES = 16 * 1024;
	private static final int MAX_DEPTH = 16;
	private static final int MAX_CONTAINER_ENTRIES = 256;
	private static final int MAX_STRING_LENGTH = 4096;
	private final Gson gson;

	public CanonicalPartyJsonCodec(Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public String encodeMap(Map<String, ?> payload)
	{
		Objects.requireNonNull(payload, "payload");
		return encodeElement(gson.toJsonTree(payload, Map.class));
	}

	public Map<String, Object> decodeMap(String json)
	{
		JsonElement root = parseBounded(json);
		if (!root.isJsonObject())
		{
			throw new IllegalArgumentException("payload must be a JSON object");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> decoded = gson.fromJson(root, LinkedHashMap.class);
		return immutableMap(decoded);
	}

	private static Map<String, Object> immutableMap(Map<String, Object> source)
	{
		Map<String, Object> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : source.entrySet())
		{
			copy.put(entry.getKey(), immutableValue(entry.getValue()));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static Object immutableValue(Object value)
	{
		if (value instanceof Map)
		{
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) value;
			return immutableMap(map);
		}
		if (value instanceof List)
		{
			List<Object> copy = new ArrayList<>();
			for (Object child : (List<?>) value) copy.add(immutableValue(child));
			return Collections.unmodifiableList(copy);
		}
		return value;
	}

	private String encodeElement(JsonElement element)
	{
		validateElement(element, 0);
		String encoded = canonicalize(element).toString();
		checkByteLength(encoded);
		return encoded;
	}

	private JsonElement parseBounded(String json)
	{
		if (json == null)
		{
			throw new IllegalArgumentException("json is required");
		}
		checkByteLength(json);
		try
		{
			JsonElement element = gson.fromJson(json, JsonElement.class);
			if (element == null)
			{
				throw new IllegalArgumentException("json is empty");
			}
			validateElement(element, 0);
			return element;
		}
		catch (JsonParseException exception)
		{
			throw new IllegalArgumentException("invalid json", exception);
		}
	}

	private static JsonElement canonicalize(JsonElement element)
	{
		if (element.isJsonObject())
		{
			JsonObject result = new JsonObject();
			List<String> names = new ArrayList<>();
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				names.add(entry.getKey());
			}
			Collections.sort(names);
			for (String name : names)
			{
				result.add(name, canonicalize(element.getAsJsonObject().get(name)));
			}
			return result;
		}
		if (element.isJsonArray())
		{
			JsonArray result = new JsonArray();
			for (JsonElement child : element.getAsJsonArray())
			{
				result.add(canonicalize(child));
			}
			return result;
		}
		return element.deepCopy();
	}

	private static void validateElement(JsonElement element, int depth)
	{
		if (depth > MAX_DEPTH)
		{
			throw new IllegalArgumentException("JSON nesting is too deep");
		}
		if (element.isJsonObject())
		{
			if (element.getAsJsonObject().size() > MAX_CONTAINER_ENTRIES)
			{
				throw new IllegalArgumentException("JSON object has too many fields");
			}
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet())
			{
				if (entry.getKey().length() > 128)
				{
					throw new IllegalArgumentException("JSON field name is too long");
				}
				validateElement(entry.getValue(), depth + 1);
			}
		}
		else if (element.isJsonArray())
		{
			if (element.getAsJsonArray().size() > MAX_CONTAINER_ENTRIES)
			{
				throw new IllegalArgumentException("JSON array has too many entries");
			}
			for (JsonElement child : element.getAsJsonArray())
			{
				validateElement(child, depth + 1);
			}
		}
		else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
			&& element.getAsString().length() > MAX_STRING_LENGTH)
		{
			throw new IllegalArgumentException("JSON string is too long");
		}
	}

	private static void checkByteLength(String json)
	{
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES)
		{
			throw new IllegalArgumentException("JSON payload is too large");
		}
	}
}
