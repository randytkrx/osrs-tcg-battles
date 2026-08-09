package com.osrstcgbattles.art;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.client.util.AsyncBufferedImage;

/** Resolves item art through ItemManager and NPC art through the shared OSRS TCG cache. */
public final class RuneLiteCardArtProvider implements CardArtProvider
{
	private final ItemManager itemManager;
	private final SharedNpcImageCache npcImages;
	private final CardArtCatalog catalog;
	private final Map<String, Optional<BufferedImage>> itemImages = new ConcurrentHashMap<>();

	public RuneLiteCardArtProvider(ItemManager itemManager, SharedNpcImageCache npcImages,
		CardArtCatalog catalog)
	{
		this.itemManager = itemManager;
		this.npcImages = npcImages;
		this.catalog = catalog;
	}

	@Override
	public void request(String azCardName, Consumer<BufferedImage> callback)
	{
		if (callback == null)
		{
			return;
		}
		Optional<CardArtSource> source = catalog.lookup(azCardName);
		if (!source.isPresent())
		{
			deliver(callback, null);
			return;
		}
		if (source.get().getKind() == CardArtSource.Kind.NPC)
		{
			npcImages.get(source.get().getImageUrl(), callback);
			return;
		}
		deliver(callback, itemImages.computeIfAbsent(azCardName.toLowerCase(Locale.ROOT),
			ignored -> Optional.ofNullable(itemImage(azCardName))).orElse(null));
	}

	private BufferedImage itemImage(String name)
	{
		try
		{
			List<ItemPrice> hits = itemManager.search(name);
			if (hits == null || hits.isEmpty())
			{
				return null;
			}
			int id = hits.get(0).getId();
			for (ItemPrice hit : hits)
			{
				if (hit.getName() != null && hit.getName().equalsIgnoreCase(name))
				{
					id = hit.getId();
					break;
				}
			}
			AsyncBufferedImage image = itemManager.getImage(id);
			// Async: the tile repaints itself when the sprite finishes loading.
			return image;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static void deliver(Consumer<BufferedImage> callback, BufferedImage image)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			callback.accept(image);
		}
		else
		{
			SwingUtilities.invokeLater(() -> callback.accept(image));
		}
	}
}
