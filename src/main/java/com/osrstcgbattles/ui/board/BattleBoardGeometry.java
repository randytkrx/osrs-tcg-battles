package com.osrstcgbattles.ui.board;

import java.awt.Rectangle;

/** Immutable result of laying out the complete battle surface. */
public final class BattleBoardGeometry
{
	private final Rectangle opponentHand;
	private final Rectangle opponentHero;
	private final Rectangle battlefield;
	private final Rectangle localHero;
	private final Rectangle localHand;
	private final Rectangle opponentDeck;
	private final Rectangle localDeck;
	private final Rectangle turnControl;
	private final Rectangle concedeControl;
	private final Rectangle status;
	private final Rectangle turnBanner;

	BattleBoardGeometry(Rectangle opponentHand, Rectangle opponentHero, Rectangle battlefield,
		Rectangle localHero, Rectangle localHand, Rectangle opponentDeck, Rectangle localDeck,
		Rectangle turnControl, Rectangle concedeControl, Rectangle status, Rectangle turnBanner)
	{
		this.opponentHand = copy(opponentHand);
		this.opponentHero = copy(opponentHero);
		this.battlefield = copy(battlefield);
		this.localHero = copy(localHero);
		this.localHand = copy(localHand);
		this.opponentDeck = copy(opponentDeck);
		this.localDeck = copy(localDeck);
		this.turnControl = copy(turnControl);
		this.concedeControl = copy(concedeControl);
		this.status = copy(status);
		this.turnBanner = copy(turnBanner);
	}

	public Rectangle getOpponentHand() { return copy(opponentHand); }
	public Rectangle getOpponentHero() { return copy(opponentHero); }
	public Rectangle getBattlefield() { return copy(battlefield); }
	public Rectangle getLocalHero() { return copy(localHero); }
	public Rectangle getLocalHand() { return copy(localHand); }
	public Rectangle getOpponentDeck() { return copy(opponentDeck); }
	public Rectangle getLocalDeck() { return copy(localDeck); }
	public Rectangle getTurnControl() { return copy(turnControl); }
	public Rectangle getConcedeControl() { return copy(concedeControl); }
	public Rectangle getStatus() { return copy(status); }
	public Rectangle getTurnBanner() { return copy(turnBanner); }

	private static Rectangle copy(Rectangle rectangle)
	{
		return new Rectangle(rectangle);
	}
}
