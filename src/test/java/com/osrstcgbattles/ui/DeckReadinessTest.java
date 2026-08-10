package com.osrstcgbattles.ui;

import com.osrstcgbattles.deck.DeckValidationError;
import com.osrstcgbattles.deck.DeckValidationResult;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeckReadinessTest
{
	private static final DeckValidationResult VALID = new DeckValidationResult(Collections.emptyList());

	@Test
	public void onlyReadyAndBuiltInDecksArePlayable()
	{
		assertTrue(new DeckReadiness(DeckReadiness.Status.READY, VALID).isPlayable());
		assertTrue(new DeckReadiness(DeckReadiness.Status.BUILT_IN_STARTER, VALID).isPlayable());
		assertFalse(new DeckReadiness(DeckReadiness.Status.OWNERSHIP_PENDING, VALID).isPlayable());
		assertFalse(new DeckReadiness(DeckReadiness.Status.INVALID, invalid()).isPlayable());
	}

	@Test
	public void labelsDistinguishPendingAndInvalidDecks()
	{
		assertEquals("Ownership check pending",
			new DeckReadiness(DeckReadiness.Status.OWNERSHIP_PENDING, VALID).getLabel());
		assertEquals("1 deck error", new DeckReadiness(DeckReadiness.Status.INVALID, invalid()).getLabel());
	}

	private static DeckValidationResult invalid()
	{
		return new DeckValidationResult(Collections.singletonList(new DeckValidationError(
			DeckValidationError.Code.CARD_COUNT, "Deck must contain exactly 30 cards", null)));
	}
}
