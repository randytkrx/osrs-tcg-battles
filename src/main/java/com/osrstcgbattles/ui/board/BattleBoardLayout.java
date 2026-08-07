package com.osrstcgbattles.ui.board;

import java.awt.Rectangle;

/** Pure responsive geometry calculation for {@link BattleBoardPanel}. */
public final class BattleBoardLayout
{
	private BattleBoardLayout()
	{
	}

	public static BattleBoardGeometry calculate(int width, int height)
	{
		int safeWidth = Math.max(1, width);
		int safeHeight = Math.max(1, height);
		int margin = clamp(safeWidth / 90, 6, 14);
		int rail = clamp(safeWidth * 13 / 100, 92, 145);
		int fieldX = rail;
		int fieldWidth = Math.max(1, safeWidth - rail * 2);
		int handWidth = Math.min(fieldWidth, 740);
		int handX = (safeWidth - handWidth) / 2;

		int opponentHandHeight = clamp(safeHeight * 12 / 100, 62, 88);
		int heroHeight = clamp(safeHeight * 9 / 100, 52, 70);
		int localHandHeight = clamp(safeHeight * 19 / 100, 104, 144);
		int opponentHeroY = opponentHandHeight + margin / 2;
		int battlefieldY = opponentHeroY + heroHeight + margin;
		int localHeroY = safeHeight - localHandHeight - heroHeight - margin / 2;
		int battlefieldHeight = Math.max(1, localHeroY - margin - battlefieldY);
		int heroWidth = Math.min(250, Math.max(180, fieldWidth / 3));
		int heroX = (safeWidth - heroWidth) / 2;

		int deckWidth = clamp(rail - margin * 2, 68, 96);
		int deckHeight = clamp(heroHeight, 52, 68);
		int deckX = margin;
		int opponentDeckY = opponentHeroY + (heroHeight - deckHeight) / 2;
		int localDeckY = localHeroY + (heroHeight - deckHeight) / 2;

		int actionWidth = clamp(rail - margin * 2, 82, 116);
		int actionX = safeWidth - actionWidth - margin;
		int turnHeight = clamp(safeHeight / 10, 54, 76);
		int turnY = battlefieldY + (battlefieldHeight - turnHeight) / 2;
		int statusWidth = Math.min(350, fieldWidth / 2);
		int statusHeight = 38;
		int statusY = battlefieldY + battlefieldHeight / 2 - statusHeight / 2;
		int bannerWidth = clamp(fieldWidth * 3 / 5, 260, 460);
		int bannerHeight = clamp(safeHeight / 11, 56, 78);
		int bannerY = battlefieldY + (battlefieldHeight - bannerHeight) / 2;

		return new BattleBoardGeometry(
			new Rectangle(handX, 0, handWidth, opponentHandHeight),
			new Rectangle(heroX, opponentHeroY, heroWidth, heroHeight),
			new Rectangle(fieldX, battlefieldY, fieldWidth, battlefieldHeight),
			new Rectangle(heroX, localHeroY, heroWidth, heroHeight),
			new Rectangle(handX, localHeroY + heroHeight, handWidth,
				Math.max(1, safeHeight - localHeroY - heroHeight)),
			new Rectangle(deckX, opponentDeckY, deckWidth, deckHeight),
			new Rectangle(deckX, localDeckY, deckWidth, deckHeight),
			new Rectangle(actionX, turnY, actionWidth, turnHeight),
			new Rectangle(actionX, safeHeight - 34 - margin, actionWidth, 28),
			new Rectangle((safeWidth - statusWidth) / 2, statusY, statusWidth, statusHeight),
			new Rectangle((safeWidth - bannerWidth) / 2, bannerY, bannerWidth, bannerHeight));
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}
}
