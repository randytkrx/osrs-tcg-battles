package com.osrstcgbattles.ui;

import com.osrstcgbattles.deck.DeckValidationResult;
import java.util.Objects;

/** One readiness result shared by the builder, sidebar, and match setup. */
public final class DeckReadiness
{
	public enum Status
	{
		BUILT_IN_STARTER,
		READY,
		OWNERSHIP_PENDING,
		INVALID
	}

	private final Status status;
	private final DeckValidationResult validation;

	public DeckReadiness(Status status, DeckValidationResult validation)
	{
		this.status = Objects.requireNonNull(status, "status");
		this.validation = Objects.requireNonNull(validation, "validation");
	}

	public Status getStatus()
	{
		return status;
	}

	public DeckValidationResult getValidation()
	{
		return validation;
	}

	public boolean isPlayable()
	{
		return status == Status.BUILT_IN_STARTER || status == Status.READY;
	}

	public String getLabel()
	{
		switch (status)
		{
			case BUILT_IN_STARTER:
				return "Built-in starter - ready";
			case READY:
				return "Ready to play";
			case OWNERSHIP_PENDING:
				return "Ownership check pending";
			default:
				int errors = validation.getErrors().size();
				return errors + (errors == 1 ? " deck error" : " deck errors");
		}
	}
}
