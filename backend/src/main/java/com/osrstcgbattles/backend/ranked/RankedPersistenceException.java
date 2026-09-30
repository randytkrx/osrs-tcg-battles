package com.osrstcgbattles.backend.ranked;

/** Signals a database failure in ranked persistence. */
public final class RankedPersistenceException extends RuntimeException
{
	public RankedPersistenceException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
