package com.osrstcgbattles.collection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class OwnedCardCollectionBridgeTest
{
	@Test
	public void queriesTheSharedBetaAndV1ProtocolOnStart()
	{
		EventBus eventBus = mock(EventBus.class);
		OwnedCardCollectionBridge bridge = new OwnedCardCollectionBridge(eventBus);

		bridge.start();

		verify(eventBus).register(bridge);
		verify(eventBus).post(argThat(event -> event instanceof PluginMessage
			&& OwnedCardCollectionBridge.NAMESPACE.equals(((PluginMessage) event).getNamespace())
			&& OwnedCardCollectionBridge.QUERY.equals(((PluginMessage) event).getName())));
	}

	@Test
	public void acceptsBetaOwnedNamesPayload()
	{
		OwnedCardCollectionBridge bridge = startedBridge();
		Map<String, Object> data = new HashMap<>();
		data.put(OwnedCardCollectionBridge.KEY_OWNED_NAMES, List.of(" Chicken ", "Kree'arra"));

		bridge.onPluginMessage(new PluginMessage(
			OwnedCardCollectionBridge.NAMESPACE, OwnedCardCollectionBridge.REPLY, data));

		OwnedCardCollectionSnapshot snapshot = bridge.snapshot();
		assertTrue(snapshot.isKnown());
		assertTrue(snapshot.owns("chicken"));
		assertTrue(snapshot.owns("KREE'ARRA"));
		assertEquals(2, snapshot.getOwnedNames().size());
	}

	@Test
	public void acceptsV1PayloadWithAdditionalOwnershipFields()
	{
		OwnedCardCollectionBridge bridge = startedBridge();
		Map<String, Object> data = new HashMap<>();
		data.put(OwnedCardCollectionBridge.KEY_OWNED_NAMES, List.of("Goblin"));
		data.put("ownedFoilNames", List.of("Goblin"));
		data.put("ownedItemIds", List.of(123L));
		data.put("ownedNpcIds", List.of(456L));

		bridge.onPluginMessage(new PluginMessage(
			OwnedCardCollectionBridge.NAMESPACE, OwnedCardCollectionBridge.CHANGED, data));

		OwnedCardCollectionSnapshot snapshot = bridge.snapshot();
		assertTrue(snapshot.isKnown());
		assertTrue(snapshot.owns("goblin"));
		assertFalse(snapshot.owns("chicken"));
	}

	@Test
	public void mapsV1CanonicalNamesToLegacyBattleCardNames()
	{
		OwnedCardCollectionBridge bridge = startedBridge();
		Map<String, Object> data = new HashMap<>();
		data.put(OwnedCardCollectionBridge.KEY_OWNED_NAMES, List.of("Ice troll", "npc:6616"));

		bridge.onPluginMessage(new PluginMessage(
			OwnedCardCollectionBridge.NAMESPACE, OwnedCardCollectionBridge.REPLY, data));

		OwnedCardCollectionSnapshot snapshot = bridge.snapshot();
		assertTrue(snapshot.owns("Ice troll male"));
		assertTrue(snapshot.owns("Frenzied ice troll male"));
		assertTrue(snapshot.owns("Scorpia's offspring"));
		assertEquals(2, snapshot.getOwnedNames().size());
	}

	@Test
	public void keepsBetaVariantOwnershipSeparate()
	{
		OwnedCardCollectionBridge bridge = startedBridge();
		Map<String, Object> data = new HashMap<>();
		data.put(OwnedCardCollectionBridge.KEY_OWNED_NAMES, List.of("Ice troll male"));

		bridge.onPluginMessage(new PluginMessage(
			OwnedCardCollectionBridge.NAMESPACE, OwnedCardCollectionBridge.REPLY, data));

		OwnedCardCollectionSnapshot snapshot = bridge.snapshot();
		assertTrue(snapshot.owns("Ice troll male"));
		assertFalse(snapshot.owns("Frenzied ice troll male"));
	}

	private static OwnedCardCollectionBridge startedBridge()
	{
		OwnedCardCollectionBridge bridge = new OwnedCardCollectionBridge(mock(EventBus.class));
		bridge.start();
		return bridge;
	}
}
