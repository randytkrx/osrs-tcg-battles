package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.BoardUnit;
import com.osrstcgbattles.engine.AttackCommand;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.PlayCardCommand;
import com.osrstcgbattles.engine.SpecialCard;
import com.osrstcgbattles.match.MatchView;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.Point;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * State-in/command-out adapter for the cohesive battle surface. Engine and network ownership stay
 * entirely with the hosting window.
 */
public final class BattleBoardView
{
	static final String NO_LEGAL_TARGETS_MESSAGE = "There are no legal targets for this card.";
	private static final String CANCEL_ACTION_KEY = "osrstcgbattles.cancelSelection";

	private final PlayerId localSeat;
	private final BattleInteraction interaction;
	private final BattleBoardPanel root;
	private final BoardPanel boardPanel;
	private final HandPanel handPanel;
	private final HandPanel opponentHandPanel;
	private final TurnButton endTurnButton;
	private final JButton concedeButton;
	private final JButton keepHandButton;

	private Consumer<Command> commandListener = command -> { };
	private MatchView state;
	private boolean handHidden;
	private boolean controlsEnabled = true;
	private String banner = " ";
	private boolean hasPreviousState;
	private String draggedAttackerId;

	public BattleBoardView(PlayerId localSeat, BattleCardCatalog catalog, CardArtProvider art)
	{
		this.localSeat = Objects.requireNonNull(localSeat, "localSeat");
		Objects.requireNonNull(catalog, "catalog");
		Objects.requireNonNull(art, "art");
		interaction = new BattleInteraction(localSeat);
		root = new BattleBoardPanel(catalog, art);
		boardPanel = root.getBoardPanel();
		handPanel = root.getLocalHand();
		opponentHandPanel = root.getOpponentHand();
		endTurnButton = root.getEndTurnButton();
		concedeButton = root.getConcedeButton();
		keepHandButton = root.getKeepHandButton();

		endTurnButton.addActionListener(event -> endTurn());
		root.getOpponentHero().setClickListener(this::onEnemyHeroClicked);
		concedeButton.addActionListener(event -> concede());
		keepHandButton.addActionListener(event -> keepHand());
		boardPanel.setUnitClickListener(this::onUnitClicked);
		boardPanel.setUnitDragListener(new BoardPanel.UnitDragListener()
		{
			@Override
			public void onUnitDragged(String instanceId, Point battleSurfacePoint)
			{
				BattleBoardView.this.onUnitDragged(instanceId, battleSurfacePoint);
			}

			@Override
			public void onUnitDropped(String instanceId, Point battleSurfacePoint)
			{
				BattleBoardView.this.onUnitDropped(instanceId, battleSurfacePoint);
			}
		});
		boardPanel.setCancelListener(this::cancelSelection);
		handPanel.setCardClickListener(this::onHandCardClicked);
		handPanel.setCardDragListener(new HandPanel.CardDragListener()
		{
			@Override
			public void onCardDragged(String cardId, Point boardPoint)
			{
				boolean valid = controlsEnabled && state != null && state.getPhase() == MatchPhase.PLAY
					&& boardPanel.getBounds().contains(boardPoint) && interaction.isHandCardPlayable(cardId);
				root.showCardDrag(cardId, boardPoint, valid);
			}

			@Override
			public void onCardDropped(String cardId, Point boardPoint)
			{
				root.hideCardDrag();
				onHandCardDropped(cardId, boardPoint);
			}
		});
		handPanel.setCancelListener(this::cancelSelection);
		opponentHandPanel.setCancelListener(this::cancelSelection);
		installCancelKeyBinding();
	}

	public JComponent getComponent()
	{
		return root;
	}

	/** Maps engine-seat identities onto this view's local and opponent hero positions. */
	public void setPlayerIdentities(BattlePlayerIdentity playerOne, BattlePlayerIdentity playerTwo)
	{
		BattlePlayerIdentity local = shownLocalIdentity(localSeat, playerOne, playerTwo);
		BattlePlayerIdentity opponent = shownOpponentIdentity(localSeat, playerOne, playerTwo);
		root.getLocalHero().setIdentity(local.getDisplayName(), local.getAvatar());
		root.getOpponentHero().setIdentity(opponent.getDisplayName(), opponent.getAvatar());
	}

	static BattlePlayerIdentity shownLocalIdentity(PlayerId localSeat, BattlePlayerIdentity playerOne,
		BattlePlayerIdentity playerTwo)
	{
		Objects.requireNonNull(localSeat, "localSeat");
		return localSeat == PlayerId.PLAYER_ONE
			? Objects.requireNonNull(playerOne, "playerOne") : Objects.requireNonNull(playerTwo, "playerTwo");
	}

	static BattlePlayerIdentity shownOpponentIdentity(PlayerId localSeat, BattlePlayerIdentity playerOne,
		BattlePlayerIdentity playerTwo)
	{
		Objects.requireNonNull(localSeat, "localSeat");
		return localSeat == PlayerId.PLAYER_ONE
			? Objects.requireNonNull(playerTwo, "playerTwo") : Objects.requireNonNull(playerOne, "playerOne");
	}

	/** Adopts a new authoritative match state and drops any half-finished local selection. */
	public void setState(MatchView state)
	{
		clearUnitDrag();
		MatchView next = Objects.requireNonNull(state, "state");
		if (next.getViewer() != localSeat) throw new IllegalArgumentException("match view is for a different player");
		recordStateChanges(this.state, next);
		PlayerId previousActive = this.state == null ? null : this.state.getActivePlayer().orElse(null);
		if (TurnBanner.shouldShow(hasPreviousState,
			this.state == null ? null : this.state.getStatus(),
			this.state == null ? null : this.state.getPhase(), previousActive,
			next.getStatus(), next.getPhase(), next.getActivePlayer().orElse(null)))
		{
			root.getTurnBanner().showTurn(next.getActivePlayer().filter(localSeat::equals).isPresent());
		}
		this.state = next;
		hasPreviousState = true;
		if (state.getStatus() == MatchStatus.COMPLETE)
		{
			root.getTurnBanner().hideBanner();
			root.getResultOverlay().showResult(localSeat, state.getWinner().orElse(null), state.getTurnNumber());
		}
		else
		{
			if (state.getPhase() == MatchPhase.MULLIGAN) root.getTurnBanner().hideBanner();
			root.getResultOverlay().setVisible(false);
		}
		interaction.update(state);
		boardPanel.setState(state.getBoard(), localSeat);
		refreshHand();
		refreshHighlighting();
		refreshHud();
		refreshControls();
	}

	public void setBanner(String message)
	{
		banner = message == null ? " " : message;
		refreshStatus();
	}

	public void setHandHidden(boolean hidden)
	{
		handHidden = hidden;
		refreshHand();
	}

	public void setCommandListener(Consumer<Command> listener)
	{
		commandListener = listener == null ? command -> { } : listener;
	}

	public void setCloseListener(Runnable listener)
	{
		root.getResultOverlay().setCloseListener(listener);
	}

	public void setControlsEnabled(boolean enabled)
	{
		controlsEnabled = enabled;
		if (!enabled) clearUnitDrag();
		refreshHighlighting();
	}

	void endTurn()
	{
		if (!controlsEnabled || state == null || state.getPhase() != MatchPhase.PLAY
			|| !state.getActivePlayer().filter(localSeat::equals).isPresent())
		{
			return;
		}
		emit(new EndTurnCommand(localSeat));
	}

	void concede()
	{
		// Concede remains independent of turn controls so a stalled opponent cannot trap this seat.
		if (state != null && state.getStatus() == MatchStatus.ACTIVE)
		{
			emit(new ConcedeCommand(localSeat));
		}
	}

	void keepHand()
	{
		if (controlsEnabled)
		{
			emit(new FinishMulliganCommand(localSeat));
		}
	}

	void onUnitClicked(String instanceId)
	{
		if (!controlsEnabled)
		{
			return;
		}
		Optional<Command> command = interaction.clickUnit(instanceId);
		if (command.isPresent())
		{
			emit(command.get());
		}
		else
		{
			refreshHighlighting();
		}
	}

	void onEnemyHeroClicked()
	{
		if (controlsEnabled)
		{
			interaction.clickEnemyHero().ifPresent(this::emit);
		}
	}

	private void onUnitDragged(String instanceId, Point battleSurfacePoint)
	{
		if (!controlsEnabled || state == null || battleSurfacePoint == null) return;
		if (!instanceId.equals(draggedAttackerId))
		{
			interaction.cancel();
			interaction.clickUnit(instanceId);
			if (!interaction.getSelectedAttackerId().filter(instanceId::equals).isPresent()) return;
			draggedAttackerId = instanceId;
			refreshHighlighting();
		}
		BoardUnit attacker = boardUnit(instanceId);
		if (attacker == null) return;
		root.showCardDrag(attacker.getDefinition().getId(), battleSurfacePoint,
			legalUnitAt(battleSurfacePoint) != null || legalHeroAt(battleSurfacePoint));
	}

	private void onUnitDropped(String instanceId, Point battleSurfacePoint)
	{
		root.hideCardDrag();
		if (!controlsEnabled || state == null || !instanceId.equals(draggedAttackerId)
			|| !interaction.getSelectedAttackerId().filter(instanceId::equals).isPresent())
		{
			clearUnitDrag();
			return;
		}
		String target = legalUnitAt(battleSurfacePoint);
		boolean hero = target == null && legalHeroAt(battleSurfacePoint);
		draggedAttackerId = null;
		Optional<Command> command = target != null ? interaction.clickUnit(target)
			: hero ? interaction.clickEnemyHero() : Optional.empty();
		if (command.isPresent()) emit(command.get());
		else cancelSelection();
	}

	private String legalUnitAt(Point battleSurfacePoint)
	{
		if (battleSurfacePoint == null) return null;
		Point boardPoint = SwingUtilities.convertPoint(root, battleSurfacePoint, boardPanel);
		String target = boardPanel.unitAt(boardPoint);
		if (target == null) return null;
		for (BoardUnit unit : interaction.targetableUnits())
		{
			if (unit.getInstanceId().equals(target)) return target;
		}
		return null;
	}

	private boolean legalHeroAt(Point battleSurfacePoint)
	{
		return battleSurfacePoint != null && root.getOpponentHero().getBounds().contains(battleSurfacePoint)
			&& interaction.isEnemyHeroTargetable();
	}

	private BoardUnit boardUnit(String instanceId)
	{
		if (state == null) return null;
		for (PlayerId player : PlayerId.values())
		{
			for (BoardUnit unit : state.getBoard().getUnits(player))
			{
				if (unit.getInstanceId().equals(instanceId)) return unit;
			}
		}
		return null;
	}

	private void clearUnitDrag()
	{
		draggedAttackerId = null;
		root.hideCardDrag();
	}

	void onHandCardClicked(String cardId)
	{
		if (!controlsEnabled || state == null)
		{
			return;
		}
		if (state.getPhase() == MatchPhase.MULLIGAN)
		{
			interaction.clickHandCardForMulligan(cardId).ifPresent(this::emit);
			return;
		}
		selectCardForPlay(cardId);
	}

	void onHandCardDropped(String cardId, Point boardPoint)
	{
		if (!controlsEnabled || state == null || state.getPhase() != MatchPhase.PLAY
			|| boardPoint == null || !boardPanel.getBounds().contains(boardPoint))
		{
			return;
		}
		selectCardForPlay(cardId);
	}

	private void selectCardForPlay(String cardId)
	{
		if (interaction.selectHandCard(cardId))
		{
			Optional<Command> command = interaction.commit();
			if (command.isPresent())
			{
				emit(command.get());
				return;
			}
			cancelIfNoLegalTargets();
		}
		refreshHighlighting();
	}

	void cancelSelection()
	{
		interaction.cancel();
		refreshHighlighting();
	}

	private void cancelIfNoLegalTargets()
	{
		if (interaction.isTargetPending() && interaction.targetableUnits().isEmpty())
		{
			interaction.cancel();
			setBanner(NO_LEGAL_TARGETS_MESSAGE);
		}
	}

	private void installCancelKeyBinding()
	{
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL_ACTION_KEY);
		root.getActionMap().put(CANCEL_ACTION_KEY, new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent event)
			{
				cancelSelection();
			}
		});
	}

	private void emit(Command command)
	{
		recordCommand(command);
		interaction.cancel();
		refreshHighlighting();
		commandListener.accept(command);
	}

	private void recordCommand(Command command)
	{
		if (command instanceof AttackCommand)
		{
			AttackCommand attack = (AttackCommand) command;
			BoardUnit attacker = boardUnit(attack.getAttackerInstanceId());
			String attackerName = attacker == null ? "Unit" : attacker.getDefinition().getName();
			String targetName = attack.getTargetInstanceId().map(id -> {
				BoardUnit target = boardUnit(id);
				return target == null ? "enemy unit" : target.getDefinition().getName();
			}).orElse("enemy hero");
			root.appendBattleLog("You attacked " + targetName + " with " + attackerName + ".");
		}
		else if (command instanceof PlayCardCommand && state != null)
		{
			String cardId = ((PlayCardCommand) command).getCardId();
			for (Card card : state.getLocalHand())
			{
				if (card.getId().equals(cardId) && card instanceof SpecialCard)
				{
					root.appendBattleLog("You played " + card.getName() + ".");
					break;
				}
			}
		}
		else if (command instanceof ConcedeCommand)
		{
			root.appendBattleLog("You conceded the battle.");
		}
	}

	private void recordStateChanges(MatchView previous, MatchView next)
	{
		if (previous == null)
		{
			root.appendBattleLog("Opening hands drawn.");
			return;
		}
		if (previous.getPhase() == MatchPhase.MULLIGAN && next.getPhase() == MatchPhase.PLAY)
		{
			root.appendBattleLog("The battle begins.");
		}
		PlayerId previousActive = previous.getActivePlayer().orElse(null);
		PlayerId nextActive = next.getActivePlayer().orElse(null);
		if (next.getPhase() == MatchPhase.PLAY && nextActive != null && nextActive != previousActive)
		{
			root.appendBattleLog("Turn " + next.getTurnNumber() + ": "
				+ (nextActive == localSeat ? "your turn." : "opponent's turn."));
		}
		for (PlayerId player : PlayerId.values())
		{
			for (BoardUnit unit : next.getBoard().getUnits(player))
			{
				if (findUnit(previous, unit.getInstanceId()) == null)
				{
					root.appendBattleLog((player == localSeat ? "You summoned " : "Opponent summoned ")
						+ unit.getDefinition().getName() + ".");
				}
			}
			for (BoardUnit unit : previous.getBoard().getUnits(player))
			{
				if (findUnit(next, unit.getInstanceId()) == null)
				{
					root.appendBattleLog(unit.getDefinition().getName() + " was defeated.");
				}
			}
			int oldHealth = previous.getPlayer(player).getHeroHealth();
			int newHealth = next.getPlayer(player).getHeroHealth();
			if (newHealth < oldHealth)
			{
				root.appendBattleLog((player == localSeat ? "You took " : "Opponent took ")
					+ (oldHealth - newHealth) + " damage.");
			}
			else if (newHealth > oldHealth)
			{
				root.appendBattleLog((player == localSeat ? "You recovered " : "Opponent recovered ")
					+ (newHealth - oldHealth) + " health.");
			}
		}
		if (previous.getStatus() != MatchStatus.COMPLETE && next.getStatus() == MatchStatus.COMPLETE)
		{
			root.appendBattleLog(next.getWinner().map(winner -> winner == localSeat ? "You won the battle."
				: "Opponent won the battle.").orElse("The battle ended in a draw."));
		}
	}

	private static BoardUnit findUnit(MatchView state, String instanceId)
	{
		for (PlayerId player : PlayerId.values())
		{
			for (BoardUnit unit : state.getBoard().getUnits(player))
			{
				if (unit.getInstanceId().equals(instanceId)) return unit;
			}
		}
		return null;
	}

	private void refreshHighlighting()
	{
		boolean mulligan = state != null && state.getPhase() == MatchPhase.MULLIGAN;
		boardPanel.setSelectableAttackers(!controlsEnabled || mulligan
			? Collections.emptyList() : interaction.selectableAttackers());
		boardPanel.setTargetable(!controlsEnabled || mulligan
			? Collections.emptyList() : interaction.targetableUnits());
		boardPanel.setSelectedAttacker(interaction.getSelectedAttackerId().orElse(null));
		boardPanel.setInteractionPending(interaction.isTargetPending()
			|| interaction.getSelectedAttackerId().isPresent());
		handPanel.setSelected(interaction.getSelectedCardId().orElse(null));
		root.setTargetPrompt(interaction.targetPrompt().orElse(null));
		refreshHandPlayability();
		refreshControls();
	}

	private void refreshHandPlayability()
	{
		if (state == null)
		{
			handPanel.setPlayableCardIds(Collections.emptySet());
			return;
		}
		HashSet<String> playable = new HashSet<>();
		boolean localTurn = controlsEnabled && state.getStatus() == MatchStatus.ACTIVE
			&& state.getActivePlayer().filter(localSeat::equals).isPresent();
		for (Card card : state.getLocalHand())
		{
			if (state.getPhase() == MatchPhase.MULLIGAN
				? localTurn && state.getPlayer(localSeat).getRemainingMulligans() > 0
				: controlsEnabled && interaction.isHandCardPlayable(card.getId()))
			{
				playable.add(card.getId());
			}
		}
		handPanel.setPlayableCardIds(playable);
	}

	private void refreshHand()
	{
		if (state == null)
		{
			return;
		}
		List<Card> hand = state.getLocalHand();
		if (handHidden)
		{
			handPanel.setFaceDownCount(hand.size());
		}
		else
		{
			handPanel.setHand(hand);
		}
		// The opponent rendering boundary is intentionally count-only.
		opponentHandPanel.setFaceDownCount(state.getPlayer(localSeat.opponent()).getHandSize());
	}

	private void refreshHud()
	{
		if (state == null)
		{
			return;
		}
		MatchView.PlayerView opponent = state.getPlayer(localSeat.opponent());
		MatchView.PlayerView local = state.getPlayer(localSeat);
		root.getOpponentHero().setStats(opponent.getHeroHealth(), opponent.getMana(), opponent.getMaximumMana());
		root.getLocalHero().setStats(local.getHeroHealth(), local.getMana(), local.getMaximumMana());
		root.getOpponentDeck().setValues(opponent.getDrawPileSize(), opponent.getFatigue());
		root.getLocalDeck().setValues(local.getDrawPileSize(), local.getFatigue());
		refreshStatus();
	}

	private void refreshStatus()
	{
		if (state == null)
		{
			root.setStatus(" ", " ", banner);
			return;
		}
		String turnNumber = state.getStatus() == MatchStatus.COMPLETE ? "Match complete"
			: "Turn " + state.getTurnNumber() + (state.getPhase() == MatchPhase.MULLIGAN ? " (mulligan)" : "");
		String turn = state.getActivePlayer().map(active -> active == localSeat ? "Your turn" : "Opponent's turn")
			.orElse("Match finished");
		root.setStatus(turnNumber, turn, banner);
	}

	private void refreshControls()
	{
		boolean mulligan = state != null && state.getPhase() == MatchPhase.MULLIGAN;
		boolean active = state != null && state.getStatus() == MatchStatus.ACTIVE;
		boolean localTurn = active && state.getActivePlayer().filter(localSeat::equals).isPresent();
		endTurnButton.setVisible(!mulligan);
		keepHandButton.setVisible(mulligan);
		boolean canEndTurn = controlsEnabled && localTurn && !mulligan;
		endTurnButton.setActionable(canEndTurn, active ? "WAITING" : "MATCH OVER");
		keepHandButton.setEnabled(controlsEnabled && localTurn && mulligan);
		concedeButton.setEnabled(active);
		boolean heroTargetable = controlsEnabled && interaction.isEnemyHeroTargetable();
		root.getOpponentHero().setTargetable(heroTargetable);
		root.getOpponentHero().setInactive(!active);
		root.getLocalHero().setInactive(!active || !localTurn);
		refreshHandPlayability();
	}
}
