package com.osrstcgbattles.deck;

import java.util.Objects;

public final class DeckValidationError
{
	public enum Code
	{
		CARD_COUNT,
		UNIT_COUNT,
		COPY_LIMIT,
		ELITE_COPY_LIMIT,
		FACTION_LIMIT,
		PROVISION_LIMIT,
		UNOWNED_CARD,
		UNKNOWN_CARD,
		INVALID_ENTRY_COUNT
	}

	private final Code code;
	private final String message;
	private final String cardId;

	public DeckValidationError(Code code, String message, String cardId)
	{
		this.code = Objects.requireNonNull(code, "code");
		this.message = Objects.requireNonNull(message, "message");
		this.cardId = cardId;
	}

	public Code getCode()
	{
		return code;
	}

	public String getMessage()
	{
		return message;
	}

	public String getCardId()
	{
		return cardId;
	}
}
