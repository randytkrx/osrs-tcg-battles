package com.osrstcgbattles.catalog;

public final class CatalogValidationException extends IllegalArgumentException
{
	public CatalogValidationException(String message)
	{
		super(message);
	}

	public CatalogValidationException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
