package com.osrstcgbattles.ui;

import com.osrstcgbattles.art.CardArtProvider;
import com.osrstcgbattles.catalog.BattleCardCatalog;
import com.osrstcgbattles.catalog.BattleCardCatalogLoader;
import com.osrstcgbattles.engine.Command;
import com.osrstcgbattles.engine.CommandResult;
import com.osrstcgbattles.engine.ConcedeCommand;
import com.osrstcgbattles.engine.GwentEngine;
import com.osrstcgbattles.engine.MatchState;
import com.osrstcgbattles.engine.MatchStatus;
import com.osrstcgbattles.engine.PlayerId;
import com.osrstcgbattles.ui.board.BattleBoardView;
import com.osrstcgbattles.ui.board.BattlePlayerIdentity;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Hot-seat battle window. Holds one {@link BattleBoardView} per seat in a {@link CardLayout} and
 * only ever shows the currently active seat's view, so the inactive player's hand is never on
 * screen -- there is no shared view whose contents could momentarily leak the wrong hand.
 */
public final class LocalBattleWindow
{
	private static final int DEFAULT_WIDTH = 1100;
	private static final int DEFAULT_HEIGHT = 750;
	private static final int MIN_WIDTH = 760;
	private static final int MIN_HEIGHT = 560;

	private final GwentEngine engine;
	private MatchState state;
	private final Map<PlayerId, BattleBoardView> views = new EnumMap<>(PlayerId.class);
	private JFrame frame;
	private JPanel seatPanel;
	private CardLayout seatLayout;
	private PlayerId shownSeat = PlayerId.PLAYER_ONE;
	private boolean concedeConfirmationOpen;

	public LocalBattleWindow(GwentEngine engine, MatchState initialState, CardArtProvider art)
	{
		this.engine = Objects.requireNonNull(engine, "engine");
		this.state = Objects.requireNonNull(initialState, "initialState");
		Objects.requireNonNull(art, "art");
		BattleCardCatalog catalog = new BattleCardCatalogLoader().loadDefault();
		runOnEdtAndWait(() -> initialize(catalog, art));
	}

	public void showWindow()
	{
		SwingUtilities.invokeLater(() -> {
			render();
			frame.setVisible(true);
			frame.toFront();
		});
	}

	public void dispose()
	{
		SwingUtilities.invokeLater(() -> frame.dispose());
	}

	private void initialize(BattleCardCatalog catalog, CardArtProvider art)
	{
		frame = new JFrame("OSRS TCG Local Battle");
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
		frame.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		frame.setLocationByPlatform(true);

		seatLayout = new CardLayout();
		seatPanel = new JPanel(seatLayout);
		BattlePlayerIdentity playerOne = new BattlePlayerIdentity("Player 1", null, "Player 1");
		BattlePlayerIdentity playerTwo = new BattlePlayerIdentity("Player 2", null, "Player 2");
		for (PlayerId player : PlayerId.values())
		{
			BattleBoardView view = new BattleBoardView(player, catalog, art);
			view.setPlayerIdentities(playerOne, playerTwo);
			view.setCommandListener(command -> onCommand(player, command));
			view.setCloseListener(this::dispose);
			views.put(player, view);
			seatPanel.add(view.getComponent(), player.name());
		}
		frame.setContentPane(seatPanel);
		render();
	}

	private void onCommand(PlayerId seat, Command command)
	{
		if (command instanceof ConcedeCommand)
		{
			if (state == null || state.getStatus() == MatchStatus.COMPLETE || concedeConfirmationOpen) return;
			concedeConfirmationOpen = true;
			int choice;
			try
			{
				choice = JOptionPane.showConfirmDialog(frame,
					BattleUiFormatters.player(seat) + " will lose the match. Concede now?",
					"Concede match", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
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
		execute(command);
	}

	private void execute(Command command)
	{
		CommandResult result = engine.execute(state, command);
		state = result.getState();
		String banner = result.isAccepted() ? " "
			: "Command rejected: " + result.getRejectionReason().map(LocalBattleWindow::readable).orElse("Unknown");
		for (BattleBoardView view : views.values())
		{
			view.setBanner(banner);
		}
		render();
	}

	private void render()
	{
		for (BattleBoardView view : views.values())
		{
			view.setState(state);
		}

		// render() runs last on every path that can end a match (execute(), showWindow(), the
		// constructor's initial render), so setting the summary banner here -- after execute()'s
		// rejection/accepted banner -- is what stops it from being clobbered: nothing runs after
		// render() to overwrite it, and every render() call re-asserts the summary for as long as
		// the match stays complete.
		if (state.getStatus() == MatchStatus.COMPLETE)
		{
			String summary = matchSummary();
			for (BattleBoardView view : views.values())
			{
				view.setBanner(summary);
			}
		}

		state.getActivePlayer().ifPresent(active -> shownSeat = active);
		for (Map.Entry<PlayerId, BattleBoardView> entry : views.entrySet())
		{
			entry.getValue().setControlsEnabled(entry.getKey() == shownSeat);
		}
		seatLayout.show(seatPanel, shownSeat.name());
	}

	private String matchSummary()
	{
		String outcome = state.getWinner()
			.map(winner -> BattleUiFormatters.player(winner) + " wins")
			.orElse("Draw");
		return "Match complete: " + outcome + " | Turns played: " + state.getTurnNumber();
	}

	private static String readable(Object value)
	{
		return value.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
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
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while creating local battle", ex);
		}
		catch (InvocationTargetException ex)
		{
			throw new IllegalStateException("Could not create local battle", ex.getCause());
		}
	}
}
