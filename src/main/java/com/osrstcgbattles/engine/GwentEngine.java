package com.osrstcgbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Deterministic, immutable Hearthstone-lite rules engine. */
public final class GwentEngine
{
	public static final int RULESET_VERSION = 3;
	public static final int HERO_HEALTH = 20;
	public static final int DECK_SIZE = 30;
	public static final int OPENING_HAND_SIZE = 3;
	public static final int HAND_LIMIT = 10;
	public static final int BATTLEFIELD_LIMIT = 7;
	public static final int MAXIMUM_MANA = 10;
	/** @deprecated Use {@link #BATTLEFIELD_LIMIT}. */
	@Deprecated public static final int ROW_CAPACITY = BATTLEFIELD_LIMIT;

	public MatchState newMatch(List<? extends Card> firstDeck, List<? extends Card> secondDeck, long seed)
	{
		return newMatch(firstDeck, secondDeck, seed, false);
	}

	public MatchState newMatchWithMulligan(List<? extends Card> firstDeck, List<? extends Card> secondDeck, long seed)
	{
		return newMatch(firstDeck, secondDeck, seed, true);
	}

	private MatchState newMatch(List<? extends Card> firstDeck, List<? extends Card> secondDeck, long seed,
		boolean mulligan)
	{
		List<Card> first = validateAndCopyDeck(firstDeck, "playerOneDeck");
		List<Card> second = validateAndCopyDeck(secondDeck, "playerTwoDeck");
		Random random = new Random(seed);
		Collections.shuffle(first, random);
		Collections.shuffle(second, random);
		EnumMap<PlayerId, PlayerState> players = new EnumMap<>(PlayerId.class);
		players.put(PlayerId.PLAYER_ONE, deal(first, mulligan));
		players.put(PlayerId.PLAYER_TWO, deal(second, mulligan));
		MatchState state = new MatchState(1, PlayerId.PLAYER_ONE, PlayerId.PLAYER_ONE, MatchStatus.ACTIVE,
			mulligan ? MatchPhase.MULLIGAN : MatchPhase.PLAY, null, players, BoardState.empty(), 1L);
		return mulligan ? state : startTurn(state, PlayerId.PLAYER_ONE, false);
	}

	public CommandResult execute(MatchState state, Command command)
	{
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(command, "command");
		if (state.getStatus() == MatchStatus.COMPLETE) return reject(state, RejectionReason.MATCH_COMPLETE);
		if (command instanceof ConcedeCommand) return concede(state, command.getPlayer());
		boolean mulliganCommand = command instanceof MulliganCommand || command instanceof FinishMulliganCommand;
		if (state.getPhase() == MatchPhase.MULLIGAN && !mulliganCommand
			|| state.getPhase() == MatchPhase.PLAY && mulliganCommand)
		{
			return reject(state, RejectionReason.COMMAND_NOT_ALLOWED_IN_PHASE);
		}
		if (!state.getActivePlayer().filter(command.getPlayer()::equals).isPresent())
		{
			return reject(state, RejectionReason.NOT_ACTIVE_PLAYER);
		}
		if (command instanceof MulliganCommand) return mulligan(state, (MulliganCommand) command);
		if (command instanceof FinishMulliganCommand) return finishMulligan(state, command.getPlayer());
		if (command instanceof PlayCardCommand) return play(state, (PlayCardCommand) command);
		if (command instanceof AttackCommand) return attack(state, (AttackCommand) command);
		if (command instanceof EndTurnCommand) return endTurn(state);
		return reject(state, RejectionReason.UNSUPPORTED_COMMAND);
	}

	private CommandResult mulligan(MatchState state, MulliganCommand command)
	{
		PlayerState player = state.getPlayer(command.getPlayer());
		if (player.getRemainingMulligans() == 0) return reject(state, RejectionReason.MULLIGAN_LIMIT_REACHED);
		int index = findCard(player.getHand(), command.getCardId());
		if (index < 0) return reject(state, RejectionReason.CARD_NOT_IN_HAND);
		if (player.getDrawPile().isEmpty()) return reject(state, RejectionReason.DRAW_PILE_EMPTY);
		Map<PlayerId, PlayerState> players = copyPlayers(state);
		PlayerState replaced = player.replaceOpeningCard(index);
		players.put(command.getPlayer(), replaced);
		MatchState next = copy(state, command.getPlayer(), players, state.getBoard(), state.getNextUnitInstanceId());
		return replaced.getRemainingMulligans() == 0 ? finishMulligan(next, command.getPlayer()) : accept(next);
	}

	private CommandResult finishMulligan(MatchState state, PlayerId player)
	{
		Map<PlayerId, PlayerState> players = copyPlayers(state);
		players.put(player, players.get(player).finishMulligan());
		if (player == PlayerId.PLAYER_ONE)
		{
			return accept(copy(state, PlayerId.PLAYER_TWO, players, state.getBoard(), state.getNextUnitInstanceId()));
		}
		MatchState play = new MatchState(1, state.getStartingPlayer(), PlayerId.PLAYER_ONE, MatchStatus.ACTIVE,
			MatchPhase.PLAY, null, players, state.getBoard(), state.getNextUnitInstanceId());
		return accept(startTurn(play, PlayerId.PLAYER_ONE, false));
	}

	private CommandResult play(MatchState state, PlayCardCommand command)
	{
		PlayerState player = state.getPlayer(command.getPlayer());
		int cardIndex = findCard(player.getHand(), command.getCardId());
		if (cardIndex < 0) return reject(state, RejectionReason.CARD_NOT_IN_HAND);
		Card card = player.getHand().get(cardIndex);
		if (player.getMana() < card.getManaCost()) return reject(state, RejectionReason.NOT_ENOUGH_MANA);
		if (card instanceof UnitCard && state.getBoard().getUnits(command.getPlayer()).size() >= BATTLEFIELD_LIMIT)
		{
			return reject(state, RejectionReason.BATTLEFIELD_FULL);
		}
		List<DeployEffect> effects = card instanceof UnitCard ? ((UnitCard) card).getDeployEffects()
			: ((SpecialCard) card).getDeployEffects();
		RejectionReason targetError = validateTarget(state.getBoard(), command.getPlayer(),
			command.getTargetInstanceId().orElse(null), effects);
		if (targetError != null) return reject(state, targetError);

		Map<PlayerId, PlayerState> players = copyPlayers(state);
		players.put(command.getPlayer(), player.removeFromHand(cardIndex).spendMana(card.getManaCost()));
		BoardState board = state.getBoard();
		String deployedId = null;
		long nextId = state.getNextUnitInstanceId();
		if (card instanceof UnitCard)
		{
			deployedId = "unit-" + nextId++;
			board = board.add(command.getPlayer(), BoardUnit.deploy(deployedId, (UnitCard) card));
		}
		else players.put(command.getPlayer(), players.get(command.getPlayer()).addToGraveyard(card));

		for (DeployEffect effect : effects)
		{
			String targetId = effect.getTarget() == DeployEffect.Target.SELF
				? deployedId : command.getTargetInstanceId().get();
			BoardUnit target = board.find(targetId);
			if (target == null) continue;
			if (effect.getType() == DeployEffect.Type.BOOST)
			{
				board = board.replace(target.withStats(target.getCurrentAttack() + effect.getAmount(),
					target.getCurrentHealth() + effect.getAmount()));
			}
			else
			{
				board = board.replace(target.withStats(target.getCurrentAttack(),
					target.getCurrentHealth() - effect.getAmount()));
				board = removeDead(board, players, targetId);
			}
		}
		return accept(copy(state, command.getPlayer(), players, board, nextId));
	}

	private CommandResult attack(MatchState state, AttackCommand command)
	{
		BoardUnit attacker = state.getBoard().find(command.getAttackerInstanceId());
		if (attacker == null) return reject(state, RejectionReason.ATTACKER_NOT_FOUND);
		if (state.getBoard().ownerOf(attacker.getInstanceId()) != command.getPlayer())
			return reject(state, RejectionReason.ATTACKER_NOT_ALLIED);
		if (!attacker.isReady()) return reject(state, RejectionReason.ATTACKER_NOT_READY);
		Map<PlayerId, PlayerState> players = copyPlayers(state);
		BoardState board = state.getBoard().replace(attacker.withReady(false));
		if (!command.getTargetInstanceId().isPresent())
		{
			PlayerId opponent = command.getPlayer().opponent();
			if (!state.getBoard().getUnits(opponent).isEmpty())
			{
				return reject(state, RejectionReason.HERO_PROTECTED);
			}
			players.put(opponent, players.get(opponent).damageHero(attacker.getCurrentAttack()));
			return accept(completeIfDead(state, players, board, state.getNextUnitInstanceId()));
		}
		String targetId = command.getTargetInstanceId().get();
		BoardUnit target = board.find(targetId);
		if (target == null) return reject(state, RejectionReason.TARGET_NOT_FOUND);
		if (board.ownerOf(targetId) != command.getPlayer().opponent())
			return reject(state, RejectionReason.TARGET_NOT_ENEMY);
		board = board.replace(attacker.withStats(attacker.getCurrentAttack(),
			attacker.getCurrentHealth() - target.getCurrentAttack()).withReady(false));
		board = board.replace(target.withStats(target.getCurrentAttack(),
			target.getCurrentHealth() - attacker.getCurrentAttack()));
		board = removeDead(board, players, attacker.getInstanceId());
		board = removeDead(board, players, targetId);
		return accept(copy(state, command.getPlayer(), players, board, state.getNextUnitInstanceId()));
	}

	private CommandResult endTurn(MatchState state)
	{
		PlayerId next = state.getActivePlayer().get().opponent();
		MatchState advanced = new MatchState(state.getTurnNumber() + 1, state.getStartingPlayer(), next,
			MatchStatus.ACTIVE, MatchPhase.PLAY, null, copyPlayers(state), state.getBoard(),
			state.getNextUnitInstanceId());
		return accept(startTurn(advanced, next, true));
	}

	private CommandResult concede(MatchState state, PlayerId player)
	{
		Map<PlayerId, PlayerState> players = copyPlayers(state);
		players.put(player, players.get(player).damageHero(players.get(player).getHeroHealth()));
		return accept(new MatchState(state.getTurnNumber(), state.getStartingPlayer(), null, MatchStatus.COMPLETE,
			MatchPhase.COMPLETE, player.opponent(), players, state.getBoard(), state.getNextUnitInstanceId()));
	}

	private MatchState startTurn(MatchState state, PlayerId player, boolean checkDeath)
	{
		Map<PlayerId, PlayerState> players = copyPlayers(state);
		players.put(player, players.get(player).startTurn(HAND_LIMIT));
		BoardState board = state.getBoard().readyAll(player);
		return checkDeath ? completeIfDead(state, players, board, state.getNextUnitInstanceId())
			: new MatchState(state.getTurnNumber(), state.getStartingPlayer(), player, MatchStatus.ACTIVE,
				MatchPhase.PLAY, null, players, board, state.getNextUnitInstanceId());
	}

	private static RejectionReason validateTarget(BoardState board, PlayerId player, String targetId,
		List<DeployEffect> effects)
	{
		boolean required = effects.stream().anyMatch(effect -> effect.getTarget() != DeployEffect.Target.SELF);
		if (required && targetId == null) return RejectionReason.TARGET_REQUIRED;
		if (!required && targetId != null) return RejectionReason.TARGET_NOT_ALLOWED;
		if (!required) return null;
		PlayerId owner = board.ownerOf(targetId);
		if (owner == null) return RejectionReason.TARGET_NOT_FOUND;
		for (DeployEffect effect : effects)
		{
			if (effect.getTarget() == DeployEffect.Target.ALLIED_UNIT && owner != player)
				return RejectionReason.TARGET_NOT_ALLIED;
			if (effect.getTarget() == DeployEffect.Target.ENEMY_UNIT && owner != player.opponent())
				return RejectionReason.TARGET_NOT_ENEMY;
		}
		return null;
	}

	private static BoardState removeDead(BoardState board, Map<PlayerId, PlayerState> players, String instanceId)
	{
		BoardUnit unit = board.find(instanceId);
		if (unit != null && unit.getCurrentHealth() <= 0)
		{
			PlayerId owner = board.ownerOf(instanceId);
			players.put(owner, players.get(owner).addToGraveyard(unit.getDefinition()));
			return board.remove(instanceId);
		}
		return board;
	}

	private static MatchState completeIfDead(MatchState source, Map<PlayerId, PlayerState> players,
		BoardState board, long nextId)
	{
		PlayerId dead = players.get(PlayerId.PLAYER_ONE).getHeroHealth() <= 0 ? PlayerId.PLAYER_ONE
			: players.get(PlayerId.PLAYER_TWO).getHeroHealth() <= 0 ? PlayerId.PLAYER_TWO : null;
		if (dead == null)
		{
			return new MatchState(source.getTurnNumber(), source.getStartingPlayer(),
				source.getActivePlayer().orElse(null), MatchStatus.ACTIVE, MatchPhase.PLAY, null,
				players, board, nextId);
		}
		return new MatchState(source.getTurnNumber(), source.getStartingPlayer(), null, MatchStatus.COMPLETE,
			MatchPhase.COMPLETE, dead.opponent(), players, board, nextId);
	}

	private static List<Card> validateAndCopyDeck(List<? extends Card> deck, String name)
	{
		Objects.requireNonNull(deck, name);
		if (deck.size() != DECK_SIZE) throw new IllegalArgumentException(name + " must contain exactly 30 cards");
		List<Card> copy = new ArrayList<>(deck.size());
		for (Card card : deck) copy.add(Objects.requireNonNull(card, name + " contains null"));
		return copy;
	}

	private static PlayerState deal(List<Card> deck, boolean mulligan)
	{
		return new PlayerState(HERO_HEALTH, 0, 1, 0, 0, deck.subList(0, OPENING_HAND_SIZE),
			deck.subList(OPENING_HAND_SIZE, deck.size()), Collections.emptyList(),
			mulligan ? OPENING_HAND_SIZE : 0, !mulligan);
	}

	private static int findCard(List<Card> hand, String cardId)
	{
		for (int i = 0; i < hand.size(); i++) if (hand.get(i).getId().equals(cardId)) return i;
		return -1;
	}

	private static Map<PlayerId, PlayerState> copyPlayers(MatchState state)
	{
		EnumMap<PlayerId, PlayerState> players = new EnumMap<>(PlayerId.class);
		for (PlayerId player : PlayerId.values()) players.put(player, state.getPlayer(player));
		return players;
	}

	private static MatchState copy(MatchState state, PlayerId active, Map<PlayerId, PlayerState> players,
		BoardState board, long nextId)
	{
		return new MatchState(state.getTurnNumber(), state.getStartingPlayer(), active, state.getStatus(),
			state.getPhase(), state.getWinner().orElse(null), players, board, nextId);
	}

	private static CommandResult accept(MatchState state) { return CommandResult.accepted(state); }
	private static CommandResult reject(MatchState state, RejectionReason reason)
	{
		return CommandResult.rejected(state, reason);
	}
}
