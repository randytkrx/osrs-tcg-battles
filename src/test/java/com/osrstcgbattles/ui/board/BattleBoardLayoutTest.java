package com.osrstcgbattles.ui.board;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BattleBoardLayoutTest
{
	@Test
	public void geometryRemainsOrderedAndContainedAtSupportedSizes()
	{
		assertGeometry(760, 560);
		assertGeometry(1100, 750);
		assertGeometry(1500, 900);
	}

	@Test
	public void primaryPlayAreasStayCentered()
	{
		BattleBoardGeometry geometry = BattleBoardLayout.calculate(1100, 750);
		assertEquals(550, center(geometry.getOpponentHand()));
		assertEquals(550, center(geometry.getOpponentHero()));
		assertEquals(550, center(geometry.getBattlefield()));
		assertEquals(550, center(geometry.getLocalHero()));
		assertEquals(550, center(geometry.getLocalHand()));
	}

	@Test
	public void boardBackgroundIsPackagedAtItsDesignedAspectRatio() throws Exception
	{
		try (InputStream stream = BattleBoardLayoutTest.class.getResourceAsStream("/com/osrstcgbattles/board.png"))
		{
			assertTrue(stream != null);
			BufferedImage image = ImageIO.read(stream);
			assertEquals(image.getWidth() * 2, image.getHeight() * 3);
		}
	}

	private static void assertGeometry(int width, int height)
	{
		BattleBoardGeometry geometry = BattleBoardLayout.calculate(width, height);
		Rectangle opponentHand = geometry.getOpponentHand();
		Rectangle opponentHero = geometry.getOpponentHero();
		Rectangle battlefield = geometry.getBattlefield();
		Rectangle localHero = geometry.getLocalHero();
		Rectangle localHand = geometry.getLocalHand();

		assertContained(opponentHand, width, height);
		assertContained(opponentHero, width, height);
		assertContained(battlefield, width, height);
		assertContained(localHero, width, height);
		assertContained(localHand, width, height);
		assertContained(geometry.getOpponentDeck(), width, height);
		assertContained(geometry.getLocalDeck(), width, height);
		assertContained(geometry.getTurnControl(), width, height);
		assertContained(geometry.getConcedeControl(), width, height);
		assertContained(geometry.getStatus(), width, height);
		assertContained(geometry.getTurnBanner(), width, height);
		assertEquals(width / 2, center(geometry.getTurnBanner()));

		assertTrue(opponentHand.y + opponentHand.height <= opponentHero.y);
		assertTrue(opponentHero.y + opponentHero.height <= battlefield.y);
		assertTrue(battlefield.y + battlefield.height <= localHero.y);
		assertTrue(localHero.y + localHero.height <= localHand.y);
		assertTrue(battlefield.width >= 560);
		assertTrue(battlefield.height >= 250);
	}

	private static int center(Rectangle rectangle)
	{
		return rectangle.x + rectangle.width / 2;
	}

	private static void assertContained(Rectangle rectangle, int width, int height)
	{
		assertTrue(rectangle.width > 0);
		assertTrue(rectangle.height > 0);
		assertTrue(rectangle.x >= 0);
		assertTrue(rectangle.y >= 0);
		assertTrue(rectangle.x + rectangle.width <= width);
		assertTrue(rectangle.y + rectangle.height <= height);
	}
}
