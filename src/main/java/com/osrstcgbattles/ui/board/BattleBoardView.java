package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Card;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.EndTurnCommand;
import com.osrstcgbattles.engine.FinishMulliganCommand;
import com.osrstcgbattles.engine.MatchPhase;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.engine.PlayerState;
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
	private MatchState state;
	private boolean handHidden;
	private boolean controlsEnabled = true;
	private String banner = " ";
	private boolean hasPreviousState;

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
	public void setState(MatchState state)
	{
		MatchState next = Objects.requireNonNull(state, "state");
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
		boardPanel.setState(state, localSeat);
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
		interaction.cancel();
		refreshHighlighting();
		commandListener.accept(command);
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
		for (Card card : state.getPlayer(localSeat).getHand())
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
		List<Card> hand = state.getPlayer(localSeat).getHand();
		if (handHidden)
		{
			handPanel.setFaceDownCount(hand.size());
		}
		else
		{
			handPanel.setHand(hand);
		}
		// The opponent rendering boundary is intentionally count-only.
		opponentHandPanel.setFaceDownCount(state.getPlayer(localSeat.opponent()).getHand().size());
	}

	private void refreshHud()
	{
		if (state == null)
		{
			return;
		}
		PlayerState opponent = state.getPlayer(localSeat.opponent());
		PlayerState local = state.getPlayer(localSeat);
		root.getOpponentHero().setStats(opponent.getHeroHealth(), opponent.getMana(), opponent.getMaximumMana());
		root.getLocalHero().setStats(local.getHeroHealth(), local.getMana(), local.getMaximumMana());
		root.getOpponentDeck().setValues(opponent.getDrawPile().size(), opponent.getFatigue());
		root.getLocalDeck().setValues(local.getDrawPile().size(), local.getFatigue());
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
