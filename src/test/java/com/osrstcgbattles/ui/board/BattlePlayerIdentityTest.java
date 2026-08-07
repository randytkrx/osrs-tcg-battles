package com.osrstcgbattles.ui.board;

import com.osrstcgbattles.engine.PlayerId;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class BattlePlayerIdentityTest
{
	@Test
	public void blankNamesUseDeterministicFallbackLabels()
	{
		assertEquals("Player 1", new BattlePlayerIdentity("  ", null, "Player 1").getDisplayName());
		assertEquals("Player 2", new BattlePlayerIdentity(null, null, "Player 2").getDisplayName());
		assertEquals("Player", new BattlePlayerIdentity(null, null, " ").getDisplayName());
	}

	@Test
	public void shownHeroesMapByLocalSeat()
	{
		BattlePlayerIdentity playerOne = new BattlePlayerIdentity("One", null, "Player 1");
		BattlePlayerIdentity playerTwo = new BattlePlayerIdentity("Two", null, "Player 2");

		assertSame(playerOne, BattleBoardView.shownLocalIdentity(PlayerId.PLAYER_ONE, playerOne, playerTwo));
		assertSame(playerTwo, BattleBoardView.shownOpponentIdentity(PlayerId.PLAYER_ONE, playerOne, playerTwo));
		assertSame(playerTwo, BattleBoardView.shownLocalIdentity(PlayerId.PLAYER_TWO, playerOne, playerTwo));
		assertSame(playerOne, BattleBoardView.shownOpponentIdentity(PlayerId.PLAYER_TWO, playerOne, playerTwo));
	}

	@Test
	public void avatarReplacementKeepsResolvedName()
	{
		BattlePlayerIdentity identity = new BattlePlayerIdentity(null, null, "Player 2");
		BufferedImage avatar = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

		BattlePlayerIdentity updated = identity.withAvatar(avatar);

		assertEquals("Player 2", updated.getDisplayName());
		assertSame(avatar, updated.getAvatar());
	}
}
