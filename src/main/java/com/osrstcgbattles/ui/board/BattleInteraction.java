package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.BoardUnit;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.DeployEffect;
import com.osrstcgbattles.engine.DuelscapeEngine;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.MulliganCommand;
import com.osrstcgbattles.engine.PlayCardCommand;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.SpecialCard;
import com.osrstcgbattles.engine.UnitCard;
import com.osrstcgbattles.engine.UnitKeyword;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns board clicks into engine commands. Deliberately free of Swing so the legality
 * rules that drive attacker and target highlighting can be unit-tested without a display.
 */
public final class BattleInteraction
{
	private final PlayerId localSeat;

	private MatchState state;
	private String selectedCardId;
	private String selectedAttackerId;
	private boolean targetPending;

	public BattleInteraction(PlayerId localSeat)
	{
		this.localSeat = Objects.requireNonNull(localSeat, "localSeat");
	}

	/** Adopts a new match state and drops any half-finished selection. */
	public void update(MatchState state)
	{
		this.state = Objects.requireNonNull(state, "state");
		cancel();
	}

	public void cancel()
	{
		selectedCardId = null;
		selectedAttackerId = null;
		targetPending = false;
	}

	public Optional<String> getSelectedCardId()
	{
		return Optional.ofNullable(selectedCardId);
	}

	public Optional<String> getSelectedAttackerId()
	{
		return Optional.ofNullable(selectedAttackerId);
	}

	public boolean isTargetPending()
	{
		return targetPending;
	}

	public Optional<String> targetPrompt()
	{
		Card card = selectedCardId == null ? null : findInHand(selectedCardId);
		if (!targetPending || card == null)
		{
			return Optional.empty();
		}
		DeployEffect.Target target = null;
		List<String> descriptions = new ArrayList<>();
		for (DeployEffect effect : effects(card))
		{
			if (effect.getTarget() == DeployEffect.Target.SELF) continue;
			target = effect.getTarget();
			descriptions.add(effect.getType() == DeployEffect.Type.DAMAGE
				? effect.getAmount() + " damage" : "+" + effect.getAmount() + "/+" + effect.getAmount());
		}
		if (target == null)
		{
			return Optional.empty();
		}
		String unit = target == DeployEffect.Target.ALLIED_UNIT ? "an allied unit" : "an enemy unit";
		return Optional.of("Select " + unit + " for " + String.join(" and ", descriptions));
	}

	/** True when the selected card can be played immediately without a deploy target. */
	public boolean isSelectionComplete()
	{
		if (selectedCardId == null)
		{
			return false;
		}
		return findInHand(selectedCardId) != null && !targetPending;
	}

	/** Emits the play for a selection that needs no deploy target. */
	public Optional<Command> commit()
	{
		if (!isSelectionComplete())
		{
			return Optional.empty();
		}
		return emit(new PlayCardCommand(localSeat, selectedCardId));
	}

	public boolean selectHandCard(String cardId)
	{
		cancel();
		if (!isHandCardPlayable(cardId))
		{
			return false;
		}
		Card card = findInHand(cardId);
		if (card == null)
		{
			return false;
		}
		selectedCardId = cardId;
		targetPending = requiresTarget(card);
		return true;
	}

	public List<BoardUnit> targetableUnits()
	{
		Card card = selectedCardId == null ? null : findInHand(selectedCardId);
		if (selectedAttackerId == null && (card == null || !targetPending))
		{
			return Collections.emptyList();
		}
		List<BoardUnit> targets = new ArrayList<>();
		boolean taunt = selectedAttackerId != null && state.getBoard().getUnits(localSeat.opponent()).stream()
			.anyMatch(unit -> unit.hasKeyword(UnitKeyword.TAUNT) && !unit.isStealthed());
		for (PlayerId owner : PlayerId.values())
		{
			for (BoardUnit unit : state.getBoard().getUnits(owner))
			{
				if (selectedAttackerId != null
					? owner == localSeat.opponent() && !unit.isStealthed()
						&& (!taunt || unit.hasKeyword(UnitKeyword.TAUNT))
					: isLegalTarget(card, owner) && (owner != localSeat.opponent() || !unit.isStealthed()))
				{
					targets.add(unit);
				}
			}
		}
		return Collections.unmodifiableList(targets);
	}

	public Optional<Command> clickUnit(String instanceId)
	{
		if (!canAct() || state.getPhase() != MatchPhase.PLAY || instanceId == null)
		{
			return Optional.empty();
		}
		if (targetPending)
		{
			if (contains(targetableUnits(), instanceId))
			{
				return emit(new PlayCardCommand(localSeat, selectedCardId, instanceId));
			}
			return Optional.empty();
		}

		BoardUnit unit = findOnBoard(instanceId);
		if (unit == null)
		{
			return Optional.empty();
		}
		if (isOwnedBy(unit, localSeat))
		{
			if (unit.isReady())
			{
				selectedCardId = null;
				targetPending = false;
				selectedAttackerId = instanceId.equals(selectedAttackerId) ? null : instanceId;
			}
			return Optional.empty();
		}
		if (selectedAttackerId != null && contains(targetableUnits(), instanceId))
		{
			return emit(new AttackCommand(localSeat, selectedAttackerId, instanceId));
		}
		return Optional.empty();
	}

	public Optional<Command> clickEnemyHero()
	{
		if (!isEnemyHeroTargetable())
		{
			return Optional.empty();
		}
		return emit(new AttackCommand(localSeat, selectedAttackerId));
	}

	public boolean isEnemyHeroTargetable()
	{
		if (!canAct() || state.getPhase() != MatchPhase.PLAY || selectedAttackerId == null)
		{
			return false;
		}
		if (!state.getBoard().getUnits(localSeat.opponent()).isEmpty())
		{
			return false;
		}
		BoardUnit attacker = findOnBoard(selectedAttackerId);
		return attacker != null && attacker.isReady() && !attacker.isRushRestricted()
			&& isOwnedBy(attacker, localSeat);
	}

	/** Mirrors only the engine's inexpensive, target-independent checks for starting a play. */
	public boolean isHandCardPlayable(String cardId)
	{
		if (!canAct() || state.getPhase() != MatchPhase.PLAY)
		{
			return false;
		}
		Card card = findInHand(cardId);
		if (card == null || state.getPlayer(localSeat).getMana() < card.getManaCost())
		{
			return false;
		}
		if (card instanceof UnitCard
			&& state.getBoard().getUnits(localSeat).size() >= DuelscapeEngine.BATTLEFIELD_LIMIT)
		{
			return false;
		}
		if (card instanceof UnitCard && ((UnitCard) card).hasKeyword(UnitKeyword.NEX_ASCENSION)
			&& !DuelscapeEngine.hasNexCommanders(state.getBoard(), localSeat)) return false;
		if (!requiresTarget(card))
		{
			return true;
		}
		for (PlayerId owner : PlayerId.values())
		{
			if (isLegalTarget(card, owner) && state.getBoard().getUnits(owner).stream()
				.anyMatch(unit -> owner != localSeat.opponent() || !unit.isStealthed()))
			{
				return true;
			}
		}
		return false;
	}

	public List<BoardUnit> selectableAttackers()
	{
		if (!canAct() || state.getPhase() != MatchPhase.PLAY || selectedCardId != null)
		{
			return Collections.emptyList();
		}
		List<BoardUnit> attackers = new ArrayList<>();
		for (BoardUnit unit : state.getBoard().getUnits(localSeat))
		{
			if (unit.isReady())
			{
				attackers.add(unit);
			}
		}
		return Collections.unmodifiableList(attackers);
	}

	/** Mulligan phase reuses the same hand tiles: one click replaces one card. */
	public Optional<Command> clickHandCardForMulligan(String cardId)
	{
		if (!canAct() || state.getPhase() != MatchPhase.MULLIGAN || findInHand(cardId) == null
			|| state.getPlayer(localSeat).getRemainingMulligans() <= 0)
		{
			return Optional.empty();
		}
		return Optional.of(new MulliganCommand(localSeat, cardId));
	}

	private Optional<Command> emit(Command command)
	{
		cancel();
		return Optional.of(command);
	}

	private boolean canAct()
	{
		return state != null
			&& state.getStatus() == MatchStatus.ACTIVE
			&& state.getActivePlayer().filter(localSeat::equals).isPresent();
	}

	private Card findInHand(String cardId)
	{
		if (cardId == null || state == null)
		{
			return null;
		}
		for (Card card : state.getPlayer(localSeat).getHand())
		{
			if (card.getId().equals(cardId))
			{
				return card;
			}
		}
		return null;
	}

	private BoardUnit findOnBoard(String instanceId)
	{
		for (PlayerId owner : PlayerId.values())
		{
			for (BoardUnit unit : state.getBoard().getUnits(owner))
			{
				if (unit.getInstanceId().equals(instanceId))
				{
					return unit;
				}
			}
		}
		return null;
	}

	private boolean isOwnedBy(BoardUnit unit, PlayerId owner)
	{
		return state.getBoard().getUnits(owner).contains(unit);
	}

	private static boolean contains(List<BoardUnit> units, String instanceId)
	{
		for (BoardUnit unit : units)
		{
			if (unit.getInstanceId().equals(instanceId))
			{
				return true;
			}
		}
		return false;
	}

	private boolean isLegalTarget(Card card, PlayerId owner)
	{
		for (DeployEffect effect : effects(card))
		{
			if (effect.getTarget() == DeployEffect.Target.ALLIED_UNIT && owner != localSeat)
			{
				return false;
			}
			if (effect.getTarget() == DeployEffect.Target.ENEMY_UNIT && owner != localSeat.opponent())
			{
				return false;
			}
		}
		return true;
	}

	private static boolean requiresTarget(Card card)
	{
		for (DeployEffect effect : effects(card))
		{
			if (effect.getTarget() == DeployEffect.Target.ALLIED_UNIT
				|| effect.getTarget() == DeployEffect.Target.ENEMY_UNIT)
			{
				return true;
			}
		}
		return false;
	}

	private static List<DeployEffect> effects(Card card)
	{
		if (card instanceof UnitCard)
		{
			return ((UnitCard) card).getDeployEffects();
		}
		if (card instanceof SpecialCard)
		{
			return ((SpecialCard) card).getDeployEffects();
		}
		return Collections.emptyList();
	}
}
