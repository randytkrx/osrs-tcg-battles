package com.osrstcgbattles.ui;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.match.MatchView;
import com.osrstcgbattles.online.OnlineMatchCoordinator;
import com.osrstcgbattles.online.OnlineMatchSnapshot;
import com.osrstcgbattles.ui.board.BattleBoardView;
import com.osrstcgbattles.ui.board.BattlePlayerIdentity;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Battle window backed exclusively by server-projected match views. */
public final class OnlineBattleWindow
{
	private final OnlineMatchCoordinator coordinator;
	private final Runnable closeAction;
	private final OnlineMatchCoordinator.Listener listener;
	private final AtomicBoolean disposed = new AtomicBoolean();
	private volatile OnlineMatchSnapshot snapshot;
	private JFrame frame;
	private BattleBoardView view;
	private boolean concedeConfirmationOpen;
	private boolean closeAfterCompletion;

	public OnlineBattleWindow(OnlineMatchCoordinator coordinator, BattleCardCatalog catalog,
		CardArtProvider art, Runnable closeAction)
	{
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.snapshot = coordinator.snapshot();
		this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
		this.listener = updated -> SwingUtilities.invokeLater(() -> accept(updated));
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

	public void dispose()
	{
		if (!disposed.compareAndSet(false, true)) return;
		coordinator.removeListener(listener);
		SwingUtilities.invokeLater(() -> frame.dispose());
	}

	private void initialize(BattleCardCatalog catalog, CardArtProvider art)
	{
		frame = new JFrame("Duelscape TCG Online Battle");
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.setMinimumSize(new Dimension(760, 560));
		frame.setSize(1100, 750);
		frame.setLocationByPlatform(true);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override public void windowClosing(WindowEvent event) { closeFromWindow(); }
			@Override public void windowClosed(WindowEvent event) { dispose(); }
		});
		PlayerId localSeat = snapshot.getLocalSeat();
		view = new BattleBoardView(localSeat, catalog, art);
		BattlePlayerIdentity local = new BattlePlayerIdentity("You", null, "You");
		BattlePlayerIdentity opponent = new BattlePlayerIdentity(snapshot.getOpponentDisplayName(), null, "Opponent");
		view.setPlayerIdentities(localSeat == PlayerId.PLAYER_ONE ? local : opponent,
			localSeat == PlayerId.PLAYER_ONE ? opponent : local);
		view.setCommandListener(this::submit);
		view.setCloseListener(this::finishClose);
		frame.setContentPane(view.getComponent());
		render();
	}

	private void accept(OnlineMatchSnapshot updated)
	{
		if (disposed.get()) return;
		snapshot = updated;
		render();
		if (closeAfterCompletion && (updated.getStatus() == OnlineMatchSnapshot.Status.COMPLETE
			|| updated.getStatus() == OnlineMatchSnapshot.Status.ERROR)) finishClose();
	}

	private void render()
	{
		OnlineMatchSnapshot current = snapshot;
		MatchView state = current.getView();
		if (state != null) view.setState(state);
		boolean activeTurn = current.getStatus() == OnlineMatchSnapshot.Status.ACTIVE && state != null
			&& state.getStatus() == MatchStatus.ACTIVE
			&& state.getActivePlayer().filter(current.getLocalSeat()::equals).isPresent();
		view.setControlsEnabled(activeTurn);
		view.setBanner(current.getMessage());
	}

	private void submit(Command command)
	{
		if (command instanceof ConcedeCommand)
		{
			if (concedeConfirmationOpen) return;
			concedeConfirmationOpen = true;
			int choice;
			try
			{
				choice = JOptionPane.showConfirmDialog(frame, "Concede this online match?", "Concede match",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			}
			finally { concedeConfirmationOpen = false; }
			if (choice != JOptionPane.OK_OPTION) return;
		}
		if (!coordinator.submit(command)) view.setBanner("Unable to send command");
	}

	private void closeFromWindow()
	{
		if (snapshot.getStatus() != OnlineMatchSnapshot.Status.COMPLETE
			&& snapshot.getStatus() != OnlineMatchSnapshot.Status.ERROR
			&& JOptionPane.showConfirmDialog(frame, "Leave this online battle?", "Close online battle",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
		if (snapshot.getStatus() == OnlineMatchSnapshot.Status.ACTIVE
			|| snapshot.getStatus() == OnlineMatchSnapshot.Status.WAITING_STATE)
		{
			if (!coordinator.concede())
			{
				view.setBanner("Unable to leave while a command is pending");
				return;
			}
			closeAfterCompletion = true;
			view.setBanner("Conceding match...");
			return;
		}
		finishClose();
	}

	private void finishClose()
	{
		if (!disposed.compareAndSet(false, true)) return;
		coordinator.removeListener(listener);
		frame.dispose();
		closeAction.run();
	}

	private static void runOnEdtAndWait(Runnable action)
	{
		if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
		try { SwingUtilities.invokeAndWait(action); }
		catch (InterruptedException exception)
		{
			throw new IllegalStateException("Interrupted while creating online battle", exception);
		}
		catch (InvocationTargetException exception)
		{
			throw new IllegalStateException("Could not create online battle", exception.getCause());
		}
	}
}
