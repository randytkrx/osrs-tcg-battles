package com.osrstcgbattles.catalog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CardAbility
{
	private final AbilityType type;
	private final Map<String, Integer> numericParams;
	private final Map<String, String> stringParams;

	CardAbility(AbilityType type, Map<String, Integer> numericParams, Map<String, String> stringParams)
	{
		this.type = type;
		this.numericParams = immutableCopy(numericParams);
		this.stringParams = immutableCopy(stringParams);
	}

	private static <T> Map<String, T> immutableCopy(Map<String, T> source)
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	public AbilityType getType()
	{
		return type;
	}

	public Map<String, Integer> getNumericParams()
	{
		return numericParams;
	}

	public Map<String, String> getStringParams()
	{
		return stringParams;
	}
}
