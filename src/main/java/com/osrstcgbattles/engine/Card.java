package com.osrstcgbattles.engine;

/** A card definition. The current engine supports {@link UnitCard} and {@link SpecialCard}. */
public interface Card
{
	String getId();

	String getName();

	int getManaCost();
}
