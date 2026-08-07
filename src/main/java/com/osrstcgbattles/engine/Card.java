package com.osrstcgbattles.engine;

/** A card definition. Future card types can implement this interface. */
public interface Card
{
	String getId();

	String getName();

	int getManaCost();
}
