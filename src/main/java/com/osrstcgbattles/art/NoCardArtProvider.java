package com.osrstcgbattles.art;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Art provider for tests and headless use: never loads anything. */
public final class NoCardArtProvider implements CardArtProvider
{
	@Override
	public void request(String azCardName, Consumer<BufferedImage> callback)
	{
		if (callback != null)
		{
			callback.accept(null);
		}
	}
}
