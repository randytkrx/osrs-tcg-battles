package com.osrstcgbattles.art;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Supplies card pictures. The callback always runs on the EDT and may be handed {@code null}
 * when no art exists, in which case callers fall back to {@link EmblemCardArt}.
 */
public interface CardArtProvider
{
	void request(String azCardName, Consumer<BufferedImage> callback);
}
