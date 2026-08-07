package com.osrstcgbattles.deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class DeckValidationResult
{
	private final List<DeckValidationError> errors;

	public DeckValidationResult(List<DeckValidationError> errors)
	{
		this.errors = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(errors, "errors")));
	}

	public boolean isValid()
	{
		return errors.isEmpty();
	}

	public List<DeckValidationError> getErrors()
	{
		return errors;
	}
}
