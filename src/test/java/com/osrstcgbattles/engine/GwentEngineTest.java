package com.osrstcgbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GwentEngineTest
{
	private final GwentEngine engine = new GwentEngine();

	@Test
	public void matchStartsWithHearthstoneResources()
	{
		MatchState state = newMatch();

		assertEquals(20, state.getPlayer(PlayerId.PLAYER_ONE).getHeroHealth());
		assertEquals(20, state.getPlayer(PlayerId.PLAYER_TWO).getHeroHealth());
		assertEquals(1, state.getPlayer(PlayerId.PLAYER_ONE).getMana());
		assertEquals(0, state.getPlayer(PlayerId.PLAYER_TWO).getMana());
		assertEquals(6, state.getPlayer(PlayerId.PLAYER_ONE).getHand().size());
		assertEquals(5, state.getPlayer(PlayerId.PLAYER_TWO).getHand().size());
		assertEquals(PlayerId.PLAYER_ONE, state.getActivePlayer().get());
	}

	@Test
	public void unitsHaveSummoningSicknessAndExchangeCombatDamage()
	{
		MatchState state = accepted(newMatch(), new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		BoardUnit first = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0);
		assertFalse(first.isReady());
		assertEquals(RejectionReason.ATTACKER_NOT_READY, engine.execute(state,
			new AttackCommand(PlayerId.PLAYER_ONE, first.getInstanceId())).getRejectionReason().get());

		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "p2"));
		BoardUnit second = state.getBoard().getUnits(PlayerId.PLAYER_TWO).get(0);
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		first = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0);
		assertTrue(first.isReady());

		state = accepted(state, new AttackCommand(PlayerId.PLAYER_ONE, first.getInstanceId(),
			second.getInstanceId()));
		assertTrue(state.getBoard().getUnits(PlayerId.PLAYER_ONE).isEmpty());
		assertEquals(1, state.getBoard().getUnits(PlayerId.PLAYER_TWO).get(0).getCurrentHealth());
		assertEquals(1, state.getPlayer(PlayerId.PLAYER_ONE).getGraveyard().size());
	}

	@Test
	public void unitCanAttackHeroOnlyOncePerTurn()
	{
		MatchState state = accepted(newMatch(), new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		String attacker = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();

		state = accepted(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker));
		assertEquals(17, state.getPlayer(PlayerId.PLAYER_TWO).getHeroHealth());
		CommandResult secondAttack = engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker));
		assertFalse(secondAttack.isAccepted());
		assertEquals(RejectionReason.ATTACKER_NOT_READY, secondAttack.getRejectionReason().get());
	}

	@Test
	public void heroCannotBeAttackedWhileEnemyHasUnits()
	{
		MatchState state = accepted(newMatch(), new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "p2"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		String attacker = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();

		CommandResult blocked = engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker));
		assertFalse(blocked.isAccepted());
		assertEquals(RejectionReason.HERO_PROTECTED, blocked.getRejectionReason().get());
		assertEquals(20, state.getPlayer(PlayerId.PLAYER_TWO).getHeroHealth());
	}

	@Test
	public void heroAttackIsAllowedOnceEnemyBoardIsCleared()
	{
		MatchState state = engine.newMatch(deck("p1", 5, 5), deck("p2", 1, 1), 43L);
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "p2"));
		String enemy = state.getBoard().getUnits(PlayerId.PLAYER_TWO).get(0).getInstanceId();
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		String attacker = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();

		state = accepted(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker, enemy));
		assertTrue(state.getBoard().getUnits(PlayerId.PLAYER_TWO).isEmpty());
		assertFalse(state.getBoard().getUnits(PlayerId.PLAYER_ONE).isEmpty());

		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		attacker = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();

		state = accepted(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker));
		assertEquals(15, state.getPlayer(PlayerId.PLAYER_TWO).getHeroHealth());
	}

	@Test
	public void fatigueDamageIncreasesOnEachEmptyDraw()
	{
		PlayerState player = new PlayerState(20, 0, 1, 0, 0, Collections.emptyList(),
			Collections.emptyList(), Collections.emptyList(), 0, true);

		player = player.drawOne(GwentEngine.HAND_LIMIT);
		assertEquals(19, player.getHeroHealth());
		assertEquals(1, player.getFatigue());
		player = player.drawOne(GwentEngine.HAND_LIMIT);
		assertEquals(17, player.getHeroHealth());
		assertEquals(2, player.getFatigue());
	}

	@Test(expected = IllegalArgumentException.class)
	public void unsupportedCardImplementationsAreRejectedAtMatchCreation()
	{
		List<Card> unsupported = new ArrayList<>();
		for (int i = 0; i < GwentEngine.DECK_SIZE; i++)
		{
			unsupported.add(new Card()
			{
				@Override public String getId() { return "future"; }
				@Override public String getName() { return "Future"; }
				@Override public int getManaCost() { return 0; }
			});
		}
		engine.newMatch(unsupported, deck("p2", 2, 2), 1L);
	}

	@Test
	public void manaEffectAddsTemporaryManaWithoutRequiringATarget()
	{
		List<Card> manaDeck = new ArrayList<>();
		for (int i = 0; i < GwentEngine.DECK_SIZE; i++)
		{
			manaDeck.add(new SpecialCard("lightbearer", "Lightbearer", 0, Collections.singletonList(
				new DeployEffect(DeployEffect.Type.MANA, DeployEffect.Target.HERO, 1))));
		}
		MatchState state = engine.newMatch(manaDeck, deck("p2", 2, 2), 1L);

		CommandResult result = engine.execute(state, new PlayCardCommand(PlayerId.PLAYER_ONE, "lightbearer"));

		assertTrue(result.isAccepted());
		assertEquals(2, result.getState().getPlayer(PlayerId.PLAYER_ONE).getMana());
		assertEquals(1, result.getState().getPlayer(PlayerId.PLAYER_ONE).getMaximumMana());
		assertEquals(1, result.getState().getPlayer(PlayerId.PLAYER_ONE).getGraveyard().size());
	}

	private MatchState newMatch()
	{
		return engine.newMatch(deck("p1", 3, 2), deck("p2", 2, 4), 42L);
	}

	private MatchState accepted(MatchState state, Command command)
	{
		CommandResult result = engine.execute(state, command);
		assertTrue(result.getRejectionReason().orElse(null) + "", result.isAccepted());
		return result.getState();
	}

	private static List<Card> deck(String id, int attack, int health)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < GwentEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard(id, id, 0, attack, health));
		}
		return cards;
	}
}
