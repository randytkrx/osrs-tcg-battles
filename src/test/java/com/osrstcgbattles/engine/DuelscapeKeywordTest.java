package com.osrstcgbattles.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DuelscapeKeywordTest
{
	private final DuelscapeEngine engine = new DuelscapeEngine();

	@Test
	public void shieldBlocksFirstCombatDamage()
	{
		MatchState state = state(unit("attacker", 3, 3), unit("shield", 2, 2, UnitKeyword.SHIELD));
		CommandResult result = attack(state, "unit-1", "unit-2");

		BoardUnit shield = result.getState().getBoard().find("unit-2");
		assertEquals(2, shield.getCurrentHealth());
		assertFalse(shield.isShielded());
		assertEquals(1, result.getState().getBoard().find("unit-1").getCurrentHealth());
	}

	@Test
	public void poisonousCombatDamageDestroysAnyUnit()
	{
		MatchState state = state(unit("poison", 1, 2, UnitKeyword.POISONOUS), unit("giant", 0, 10));
		CommandResult result = attack(state, "unit-1", "unit-2");

		assertTrue(result.getState().getBoard().getUnits(PlayerId.PLAYER_TWO).isEmpty());
	}

	@Test
	public void shieldPreventsPoisonousWhenItPreventsDamage()
	{
		MatchState state = state(unit("poison", 1, 2, UnitKeyword.POISONOUS),
			unit("shield", 0, 10, UnitKeyword.SHIELD));

		BoardUnit target = attack(state, "unit-1", "unit-2").getState().getBoard().find("unit-2");

		assertEquals(10, target.getCurrentHealth());
		assertFalse(target.isShielded());
	}

	@Test
	public void lifestealHealsDamageActuallyDealt()
	{
		MatchState state = state(unit("leech", 3, 3, UnitKeyword.LIFESTEAL), unit("target", 0, 5), 10, 20);
		CommandResult result = attack(state, "unit-1", "unit-2");

		assertEquals(13, result.getState().getPlayer(PlayerId.PLAYER_ONE).getHeroHealth());
	}

	@Test
	public void rushCanAttackUnitsButNotHeroOnDeployTurn()
	{
		MatchState state = state(unit("rush", 2, 2, UnitKeyword.RUSH), unit("target", 0, 3));

		assertEquals(RejectionReason.RUSH_CANNOT_ATTACK_HERO,
			engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, "unit-1")).getRejectionReason().get());
		assertTrue(attack(state, "unit-1", "unit-2").isAccepted());
	}

	@Test
	public void tauntMustBeAttackedBeforeOtherUnits()
	{
		UnitCard attacker = unit("attacker", 2, 4);
		BoardState board = BoardState.empty()
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-1", attacker)).readyAll(PlayerId.PLAYER_ONE)
			.add(PlayerId.PLAYER_TWO, BoardUnit.deploy("unit-2", unit("taunt", 1, 4, UnitKeyword.TAUNT)))
			.add(PlayerId.PLAYER_TWO, BoardUnit.deploy("unit-3", unit("other", 1, 4)));
		MatchState state = state(board, defaultPlayer(20), defaultPlayer(20));

		assertEquals(RejectionReason.TAUNT_PROTECTED,
			engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, "unit-1", "unit-3"))
				.getRejectionReason().get());
		assertTrue(attack(state, "unit-1", "unit-2").isAccepted());
	}

	@Test
	public void stealthPreventsEnemyTargetingUntilTheUnitAttacks()
	{
		MatchState state = state(unit("attacker", 2, 3), unit("stealth", 2, 3, UnitKeyword.STEALTH));

		assertEquals(RejectionReason.TARGET_STEALTHED,
			engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, "unit-1", "unit-2"))
				.getRejectionReason().get());
	}

	@Test
	public void stealthIsRevealedWhenTheUnitAttacks()
	{
		MatchState state = state(unit("stealth", 2, 3, UnitKeyword.STEALTH), unit("target", 0, 4));

		MatchState next = attack(state, "unit-1", "unit-2").getState();

		assertFalse(next.getBoard().find("unit-1").isStealthed());
	}

	@Test
	public void rushRestrictionClearsAtTheStartOfTheUnitsNextTurn()
	{
		MatchState state = state(unit("rush", 2, 3, UnitKeyword.RUSH), unit("target", 0, 4));
		assertTrue(state.getBoard().find("unit-1").isRushRestricted());

		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));

		assertTrue(state.getBoard().find("unit-1").isReady());
		assertFalse(state.getBoard().find("unit-1").isRushRestricted());
	}

	@Test
	public void simultaneousLethalCompletesAsADraw()
	{
		List<DeathrattleEffect> lethal = Collections.singletonList(
			new DeathrattleEffect(DeathrattleEffect.Type.DAMAGE_ENEMY_HERO, 1));
		UnitCard attacker = new UnitCard("attacker", "Attacker", 0, 1, 1, Collections.emptyList(),
			Collections.emptySet(), lethal);
		UnitCard target = new UnitCard("target", "Target", 0, 1, 1, Collections.emptyList(),
			Collections.emptySet(), lethal);
		MatchState state = state(attacker, target, 1, 1);

		MatchState next = attack(state, "unit-1", "unit-2").getState();

		assertEquals(MatchStatus.COMPLETE, next.getStatus());
		assertEquals(MatchPhase.COMPLETE, next.getPhase());
		assertFalse(next.getActivePlayer().isPresent());
		assertFalse(next.getWinner().isPresent());
		assertEquals(0, next.getPlayer(PlayerId.PLAYER_ONE).getHeroHealth());
		assertEquals(0, next.getPlayer(PlayerId.PLAYER_TWO).getHeroHealth());
	}

	@Test
	public void mixedDeathrattlesDamageDrawAndSummon()
	{
		List<DeathrattleEffect> deathrattles = java.util.Arrays.asList(
			new DeathrattleEffect(DeathrattleEffect.Type.DAMAGE_ENEMY_HERO, 2),
			new DeathrattleEffect(DeathrattleEffect.Type.DRAW_CARD, 1),
			new DeathrattleEffect(DeathrattleEffect.Type.SUMMON_SPIRIT, 1));
		UnitCard victim = new UnitCard("victim", "Victim", 1, 0, 1, Collections.emptyList(),
			Collections.emptySet(), deathrattles);
		BoardState board = BoardState.empty()
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-1", unit("attacker", 1, 3)))
			.readyAll(PlayerId.PLAYER_ONE).add(PlayerId.PLAYER_TWO, BoardUnit.deploy("unit-2", victim));
		PlayerState second = new PlayerState(20, 1, 1, 0, 1, Collections.emptyList(),
			Collections.singletonList(unit("drawn", 1, 1)), Collections.emptyList(), 0, true);
		MatchState state = state(board, defaultPlayer(20), second);

		MatchState next = attack(state, "unit-1", "unit-2").getState();

		assertEquals(18, next.getPlayer(PlayerId.PLAYER_ONE).getHeroHealth());
		assertEquals(1, next.getPlayer(PlayerId.PLAYER_TWO).getHand().size());
		assertEquals("deathrattle-spirit",
			next.getBoard().getUnits(PlayerId.PLAYER_TWO).get(0).getDefinition().getId());
	}

	@Test
	public void nexRequiresAllCommandersAndWinsWhenSummoned()
	{
		UnitCard nex = new UnitCard("asgarnia-nex", "Nex", 10, 10, 10, Collections.emptyList(),
			EnumSet.of(UnitKeyword.NEX_ASCENSION), Collections.emptyList());
		BoardState completeBoard = BoardState.empty()
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-1", unit("asgarnia-general-graardor", 1, 1)))
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-2", unit("asgarnia-commander-zilyana", 1, 1)))
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-3", unit("asgarnia-kreearra", 1, 1)))
			.add(PlayerId.PLAYER_ONE, BoardUnit.deploy("unit-4", unit("asgarnia-kril-tsutsaroth", 1, 1)));
		PlayerState withNex = new PlayerState(20, 10, 10, 0, 10, Collections.singletonList(nex),
			Collections.emptyList(), Collections.emptyList(), 0, true);

		MatchState incomplete = state(completeBoard.remove("unit-4"), withNex, defaultPlayer(20));
		assertEquals(RejectionReason.NEX_REQUIRES_COMMANDERS,
			engine.execute(incomplete, new PlayCardCommand(PlayerId.PLAYER_ONE, "asgarnia-nex"))
				.getRejectionReason().get());

		CommandResult result = engine.execute(state(completeBoard, withNex, defaultPlayer(20)),
			new PlayCardCommand(PlayerId.PLAYER_ONE, "asgarnia-nex"));
		assertTrue(result.isAccepted());
		assertEquals(MatchStatus.COMPLETE, result.getState().getStatus());
		assertEquals(PlayerId.PLAYER_ONE, result.getState().getWinner().get());
	}

	private CommandResult attack(MatchState state, String attacker, String target)
	{
		CommandResult result = engine.execute(state, new AttackCommand(PlayerId.PLAYER_ONE, attacker, target));
		assertTrue(result.getRejectionReason().orElse(null) + "", result.isAccepted());
		return result;
	}

	private MatchState accepted(MatchState state, Command command)
	{
		CommandResult result = engine.execute(state, command);
		assertTrue(result.getRejectionReason().orElse(null) + "", result.isAccepted());
		return result.getState();
	}

	private static MatchState state(UnitCard attacker, UnitCard target)
	{
		return state(attacker, target, 20, 20);
	}

	private static MatchState state(UnitCard attacker, UnitCard target, int firstHealth, int secondHealth)
	{
		BoardUnit deployed = BoardUnit.deploy("unit-1", attacker);
		BoardState board = BoardState.empty().add(PlayerId.PLAYER_ONE, deployed);
		if (!deployed.isReady()) board = board.readyAll(PlayerId.PLAYER_ONE);
		board = board.add(PlayerId.PLAYER_TWO, BoardUnit.deploy("unit-2", target));
		return state(board, defaultPlayer(firstHealth), defaultPlayer(secondHealth));
	}

	private static MatchState state(BoardState board, PlayerState first, PlayerState second)
	{
		Map<PlayerId, PlayerState> players = new EnumMap<>(PlayerId.class);
		players.put(PlayerId.PLAYER_ONE, first);
		players.put(PlayerId.PLAYER_TWO, second);
		return new MatchState(1, PlayerId.PLAYER_ONE, PlayerId.PLAYER_ONE, MatchStatus.ACTIVE,
			MatchPhase.PLAY, null, players, board, 10L);
	}

	private static PlayerState defaultPlayer(int health)
	{
		return new PlayerState(health, 1, 1, 0, 1, Collections.emptyList(), Collections.emptyList(),
			Collections.emptyList(), 0, true);
	}

	private static UnitCard unit(String id, int attack, int health, UnitKeyword... keywords)
	{
		EnumSet<UnitKeyword> set = keywords.length == 0 ? EnumSet.noneOf(UnitKeyword.class)
			: EnumSet.copyOf(java.util.Arrays.asList(keywords));
		return new UnitCard(id, id, 1, attack, health, Collections.emptyList(), set, Collections.emptyList());
	}
}
