package com.osrstcgbattles.deck;

import java.util.Optional;

@FunctionalInterface
public interface CardLookup
{
	Optional<CardInfo> findById(String cardId);
}
