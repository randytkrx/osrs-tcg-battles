package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.DeployEffect;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.PlayCardCommand;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.engine.UnitKeyword;
import com.osrstcgbattles.match.MatchView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattleInteractionTest
{
	private final DuelscapeEngine engine = new DuelscapeEngine();

	@Test
	public void targetlessCardPlaysDirectly()
	{
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(newMatch()));

		assertTrue(interaction.selectHandCard("p1"));
		Command command = interaction.commit().get();
		assertTrue(command instanceof PlayCardCommand);
		assertFalse(((PlayCardCommand) command).getTargetInstanceId().isPresent());
	}

	@Test
	public void readyUnitCanSelectAndAttackEnemyHero()
	{
		MatchState state = accepted(newMatch(), new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		String attackerId = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(state));

		assertFalse(interaction.clickUnit(attackerId).isPresent());
		assertTrue(interaction.getSelectedAttackerId().isPresent());
		assertTrue(interaction.isEnemyHeroTargetable());
		Command command = interaction.clickEnemyHero().get();
		assertTrue(command instanceof AttackCommand);
		assertFalse(((AttackCommand) command).getTargetInstanceId().isPresent());
		assertFalse(interaction.isEnemyHeroTargetable());
	}

	@Test
	public void enemyHeroIsNotTargetableWithoutLocalTurnAndReadySelection()
	{
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		MatchState state = newMatch();
		interaction.update(view(state));
		assertFalse(interaction.isEnemyHeroTargetable());

		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		interaction.update(view(state));
		assertFalse(interaction.isEnemyHeroTargetable());
		assertFalse(interaction.clickEnemyHero().isPresent());

		interaction.update(view(engine.newMatchWithMulligan(deck("p1"), deck("p2"), 9L)));
		assertFalse(interaction.isEnemyHeroTargetable());
		assertFalse(interaction.clickEnemyHero().isPresent());
	}

	@Test
	public void enemyHeroIsGuardedWhileEnemyHasUnits()
	{
		MatchState state = accepted(newMatch(), new PlayCardCommand(PlayerId.PLAYER_ONE, "p1"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "p2"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		String attackerId = state.getBoard().getUnits(PlayerId.PLAYER_ONE).get(0).getInstanceId();
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(state));

		assertFalse(interaction.clickUnit(attackerId).isPresent());
		assertTrue(interaction.getSelectedAttackerId().isPresent());
		assertFalse(interaction.isEnemyHeroTargetable());
		assertFalse(interaction.clickEnemyHero().isPresent());
	}

	@Test
	public void unaffordableCardCannotStartAPlay()
	{
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(engine.newMatch(deck("expensive", 2), deck("p2"), 4L)));

		assertFalse(interaction.isHandCardPlayable("expensive"));
		assertFalse(interaction.selectHandCard("expensive"));
	}

	@Test
	public void targetedCardDescribesTheRequiredTargetAndEffect()
	{
		List<Card> targeted = targetedDeck("damage");
		MatchState state = engine.newMatch(targeted, deck("p2"), 5L);
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "p2"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(state));

		assertTrue(interaction.selectHandCard("damage"));
		assertTrue(interaction.isTargetPending());
		assertEquals("Select an enemy unit for 3 damage", interaction.targetPrompt().get());
	}

	@Test
	public void enemyTargetedCardIsNotPlayableWhenEveryEnemyIsStealthed()
	{
		MatchState state = engine.newMatch(targetedDeck("damage"), stealthDeck("stealth"), 5L);
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_ONE));
		state = accepted(state, new PlayCardCommand(PlayerId.PLAYER_TWO, "stealth"));
		state = accepted(state, new EndTurnCommand(PlayerId.PLAYER_TWO));
		BattleInteraction interaction = new BattleInteraction(PlayerId.PLAYER_ONE);
		interaction.update(view(state));

		assertFalse(interaction.isHandCardPlayable("damage"));
		assertFalse(interaction.selectHandCard("damage"));
	}

	private MatchState newMatch()
	{
		return engine.newMatch(deck("p1"), deck("p2"), 7L);
	}

	private static MatchView view(MatchState state)
	{
		return MatchView.forPlayer(state, PlayerId.PLAYER_ONE);
	}

	private MatchState accepted(MatchState state, Command command)
	{
		CommandResult result = engine.execute(state, command);
		assertTrue(result.getRejectionReason().orElse(null) + "", result.isAccepted());
		return result.getState();
	}

	private static List<Card> deck(String id)
	{
		return deck(id, 0);
	}

	private static List<Card> deck(String id, int manaCost)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard(id, id, manaCost, 3, 2));
		}
		return cards;
	}

	private static List<Card> targetedDeck(String id)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard(id, id, 0, 2, 2, Collections.singletonList(
				new DeployEffect(DeployEffect.Type.DAMAGE, DeployEffect.Target.ENEMY_UNIT, 3))));
		}
		return cards;
	}

	private static List<Card> stealthDeck(String id)
	{
		List<Card> cards = new ArrayList<>();
		for (int i = 0; i < DuelscapeEngine.DECK_SIZE; i++)
		{
			cards.add(new UnitCard(id, id, 0, 2, 2, Collections.emptyList(),
				EnumSet.of(UnitKeyword.STEALTH), Collections.emptyList()));
		}
		return cards;
	}
}
