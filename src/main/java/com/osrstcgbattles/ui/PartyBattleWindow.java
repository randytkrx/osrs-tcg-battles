package com.osrstcgbattles.ui;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.match.MatchView;
import com.osrstcgbattles.match.PartyMatchCoordinator;
import com.osrstcgbattles.match.PartyMatchListener;
import com.osrstcgbattles.match.PartyMatchSnapshot;
import com.osrstcgbattles.ui.board.BattleBoardView;
import com.osrstcgbattles.ui.board.BattlePlayerIdentity;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Swing view of a synchronized party match, rendered with a single {@link BattleBoardView} built
 * for the local seat. The board receives a player-scoped {@link MatchView}, so hidden opponent
 * cards are excluded before state reaches the rendering boundary.
 */
public final class PartyBattleWindow
{
	private static final int DEFAULT_WIDTH = 1100;
	private static final int DEFAULT_HEIGHT = 750;
	private static final int MIN_WIDTH = 760;
	private static final int MIN_HEIGHT = 560;

	private final PartyMatchCoordinator coordinator;
	private final Runnable ownerCloseAction;
	private final PartyMatchListener listener;
	private final PlayerId localSeat;
	private final long localMemberId;
	private final long opponentMemberId;
	private final AtomicBoolean disposed = new AtomicBoolean();
	private final AtomicBoolean ownerCloseInvoked = new AtomicBoolean();
	private volatile PartyMatchSnapshot snapshot;
	private JFrame frame;
	private BattleBoardView view;
	private BattlePlayerIdentity localIdentity;
	private BattlePlayerIdentity opponentIdentity;
	private boolean concedeConfirmationOpen;

	public PartyBattleWindow(PartyMatchCoordinator coordinator, BattleCardCatalog catalog, Runnable ownerCloseAction,
		CardArtProvider art,
		PartyBattleParticipant localParticipant, PartyBattleParticipant opponentParticipant)
	{
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		Objects.requireNonNull(catalog, "catalog");
		this.ownerCloseAction = Objects.requireNonNull(ownerCloseAction, "ownerCloseAction");
		Objects.requireNonNull(art, "art");
		this.snapshot = coordinator.snapshot();
		this.localSeat = snapshot.getLocalSeat();
		Objects.requireNonNull(localParticipant, "localParticipant");
		Objects.requireNonNull(opponentParticipant, "opponentParticipant");
		this.localMemberId = localParticipant.getMemberId();
		this.opponentMemberId = opponentParticipant.getMemberId();
		this.localIdentity = new BattlePlayerIdentity(localParticipant.getDisplayName(), localParticipant.getAvatar(),
			playerLabel(localSeat));
		this.opponentIdentity = new BattlePlayerIdentity(opponentParticipant.getDisplayName(),
			opponentParticipant.getAvatar(), playerLabel(localSeat.opponent()));
		this.listener = updated -> SwingUtilities.invokeLater(() -> acceptSnapshot(updated));
		coordinator.addListener(listener);
		try
		{
			runOnEdtAndWait(() -> initialize(catalog, art));
		}
		catch (RuntimeException exception)
		{
			coordinator.removeListener(listener);
			throw exception;
		}
	}

	public void showWindow()
	{
		SwingUtilities.invokeLater(() -> {
			if (disposed.get()) return;
			render();
			frame.setVisible(true);
			frame.toFront();
		});
	}

	/** Disposes without treating plugin shutdown or profile changes as a user abort. */
	public void dispose()
	{
		if (!disposed.compareAndSet(false, true)) return;
		coordinator.removeListener(listener);
		SwingUtilities.invokeLater(() -> frame.dispose());
	}

	public void refresh()
	{
		PartyMatchSnapshot current = coordinator.snapshot();
		SwingUtilities.invokeLater(() -> acceptSnapshot(current));
	}

	public boolean hasParticipant(long memberId)
	{
		return memberId == localMemberId || memberId == opponentMemberId;
	}

	/** Safe to call from any thread; ignored after this window has been disposed. */
	public void updateAvatar(long memberId, java.awt.image.BufferedImage avatar)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> updateAvatar(memberId, avatar));
			return;
		}
		if (disposed.get() || !hasParticipant(memberId)) return;
		if (memberId == localMemberId)
		{
			localIdentity = localIdentity.withAvatar(avatar);
		}
		else
		{
			opponentIdentity = opponentIdentity.withAvatar(avatar);
		}
		applyIdentities();
	}

	private void initialize(BattleCardCatalog catalog, CardArtProvider art)
	{
		frame = new JFrame("Duelscape TCG Friend Duel");
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
		frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		frame.setLocationByPlatform(true);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent event)
			{
				closeFromWindow();
			}

			@Override
			public void windowClosed(WindowEvent event)
			{
				removeListener();
			}
		});

		view = new BattleBoardView(localSeat, catalog, art);
		applyIdentities();
		view.setCommandListener(this::onCommand);
		view.setCloseListener(this::closeCompletedMatch);
		frame.setContentPane(view.getComponent());
		render();
	}

	private void applyIdentities()
	{
		if (localSeat == PlayerId.PLAYER_ONE)
		{
			view.setPlayerIdentities(localIdentity, opponentIdentity);
		}
		else
		{
			view.setPlayerIdentities(opponentIdentity, localIdentity);
		}
	}

	private static String playerLabel(PlayerId seat)
	{
		return seat == PlayerId.PLAYER_ONE ? "Player 1" : "Player 2";
	}

	private void acceptSnapshot(PartyMatchSnapshot updated)
	{
		if (disposed.get()) return;
		snapshot = Objects.requireNonNull(updated, "snapshot");
		render();
	}

	private void render()
	{
		PartyMatchSnapshot current = snapshot;
		MatchState state = current.getMatchState();
		if (state != null)
		{
			view.setState(MatchView.forPlayer(state, localSeat));
		}
		view.setControlsEnabled(actionsEnabled(current.getStatus(), state, localSeat));
		view.setBanner(statusBanner(current));
	}

	private void onCommand(Command command)
	{
		if (command instanceof ConcedeCommand)
		{
			MatchState state = snapshot.getMatchState();
			if (state == null || state.getStatus() == MatchStatus.COMPLETE || concedeConfirmationOpen) return;
			concedeConfirmationOpen = true;
			int choice;
			try
			{
				choice = JOptionPane.showConfirmDialog(frame,
					"Conceding will immediately lose this friend duel. Continue?", "Concede match",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			}
			finally
			{
				concedeConfirmationOpen = false;
			}
			if (choice != JOptionPane.OK_OPTION)
			{
				return;
			}
		}
		submit(command);
	}

	private void submit(Command command)
	{
		try
		{
			CommandResult result = coordinator.submit(command);
			if (!result.isAccepted())
			{
				view.setBanner("Command rejected: "
					+ result.getRejectionReason().map(PartyBattleWindow::readable).orElse("unknown reason"));
			}
			else
			{
				acceptSnapshot(coordinator.snapshot());
			}
		}
		catch (RuntimeException exception)
		{
			// coordinator.submit() throws IllegalStateException when the match is not ACTIVE (e.g. a
			// desync or an already-aborted/completed match); surface it in the banner instead of
			// letting it escape into Swing's event loop.
			view.setBanner("Command could not be sent: " + safeMessage(exception));
		}
	}

	private void closeFromWindow()
	{
		if (disposed.get()) return;
		PartyMatchSnapshot.Status status = snapshot.getStatus();
		MatchState state = snapshot.getMatchState();
		boolean matchComplete = state != null && state.getStatus() == MatchStatus.COMPLETE;
		if (!matchComplete && requiresCloseConfirmation(status) && JOptionPane.showConfirmDialog(frame,
			"Leave this friend duel and close this window?", "Close friend duel",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}
		finishUserClose();
	}

	private void closeCompletedMatch()
	{
		MatchState state = snapshot.getMatchState();
		if (state != null && state.getStatus() == MatchStatus.COMPLETE) finishUserClose();
	}

	private void finishUserClose()
	{
		if (!claimUserClose(disposed)) return;
		try
		{
			coordinator.removeListener(listener);
			frame.dispose();
		}
		finally
		{
			invokeOnce(ownerCloseInvoked, ownerCloseAction);
		}
	}

	private void removeListener()
	{
		if (disposed.compareAndSet(false, true)) coordinator.removeListener(listener);
	}

	static boolean actionsEnabled(PartyMatchSnapshot.Status status, MatchState state, PlayerId localSeat)
	{
		return status == PartyMatchSnapshot.Status.ACTIVE && state != null
			&& state.getStatus() == MatchStatus.ACTIVE
			&& state.getActivePlayer().filter(localSeat::equals).isPresent();
	}

	static boolean requiresCloseConfirmation(PartyMatchSnapshot.Status status)
	{
		return status == PartyMatchSnapshot.Status.WAITING_SETUP
			|| status == PartyMatchSnapshot.Status.ACTIVE;
	}

	static boolean claimUserClose(AtomicBoolean disposed)
	{
		return disposed.compareAndSet(false, true);
	}

	static void invokeOnce(AtomicBoolean invoked, Runnable action)
	{
		if (invoked.compareAndSet(false, true)) action.run();
	}

	private static String statusBanner(PartyMatchSnapshot current)
	{
		MatchState state = current.getMatchState();
		switch (current.getStatus())
		{
			case WAITING_SETUP:
				return "Waiting for opponent setup";
			case ACTIVE:
				// Deliberately no diagnosticSuffix here: BattleBoardView's own HUD already shows
				// round/turn/score on every render during active play, so a permanent
				// "Revision N | Hash ..." string on top of that would be clutter, not diagnosis.
				return " ";
			case COMPLETE:
				if (state == null) return "Match complete";
				String result = state.getWinner().map(player -> BattleUiFormatters.player(player) + " wins")
					.orElse("Draw");
				return "Match complete: " + result + " | Turns played: " + state.getTurnNumber();
			case DESYNC:
				// The one case this diagnostic exists for: two players staring at "synchronization
				// failed" with nothing to compare. Restores what the old text-label UI exposed here
				// (see git history's PartyBattleWindow.diagnosticSuffix) so a revision/hash mismatch is
				// at least visible to compare by eye or paste into a bug report.
				return "Match synchronization failed" + diagnosticSuffix(current);
			case ABORTED:
				return "Match aborted";
			default:
				return current.getUserStatus();
		}
	}

	/**
	 * Diagnostic text only -- see the DESYNC case in {@link #statusBanner} for why this exists.
	 * Restores the old text-label UI's {@code diagnosticSuffix}: the match's revision counter and
	 * its public state hash, the only two pieces of desync-relevant information a player can see at
	 * all. There is no test surface that can construct a real {@link PartyMatchSnapshot} from this
	 * package ({@code PartyMatchSnapshot}'s constructor is package-private to {@code match} and only
	 * ever built by {@code PartyMatchCoordinator}), so the actual string formatting is pulled out
	 * into {@link #diagnosticSuffix(long, String)}, which {@code PartyBattleWindowHelpersTest} tests
	 * directly.
	 */
	private static String diagnosticSuffix(PartyMatchSnapshot snapshot)
	{
		String hash = snapshot.getInitializedMatchState().map(MatchState::getPublicStateHash).orElse("unavailable");
		return diagnosticSuffix(snapshot.getRevision(), hash);
	}

	static String diagnosticSuffix(long revision, String hash)
	{
		return " | Revision " + revision + " | Hash " + hash;
	}

	private static String readable(Object value)
	{
		return value.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static String safeMessage(RuntimeException exception)
	{
		String message = exception.getMessage();
		return message == null || message.trim().isEmpty() ? "unexpected error" : message;
	}

	private static void runOnEdtAndWait(Runnable action)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			action.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(action);
		}
		catch (InterruptedException exception)
		{
			throw new IllegalStateException("Interrupted while creating party battle", exception);
		}
		catch (InvocationTargetException exception)
		{
			throw new IllegalStateException("Could not create party battle", exception.getCause());
		}
	}
}
