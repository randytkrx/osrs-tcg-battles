package com.osrstcgbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlayerState
{
	private final int heroHealth;
	private final int mana;
	private final int maximumMana;
	private final int fatigue;
	private final int turnsStarted;
	private final List<Card> hand;
	private final List<Card> drawPile;
	private final List<Card> graveyard;
	private final int remainingMulligans;
	private final boolean mulliganFinished;

	PlayerState(int heroHealth, int mana, int maximumMana, int fatigue, int turnsStarted,
		List<? extends Card> hand, List<? extends Card> drawPile, List<? extends Card> graveyard,
		int remainingMulligans, boolean mulliganFinished)
	{
		this.heroHealth = heroHealth;
		this.mana = mana;
		this.maximumMana = maximumMana;
		this.fatigue = fatigue;
		this.turnsStarted = turnsStarted;
		this.hand = immutableCopy(hand);
		this.drawPile = immutableCopy(drawPile);
		this.graveyard = immutableCopy(graveyard);
		this.remainingMulligans = remainingMulligans;
		this.mulliganFinished = mulliganFinished;
	}

	private static List<Card> immutableCopy(List<? extends Card> cards)
	{
		return Collections.unmodifiableList(new ArrayList<>(cards));
	}

	public int getHeroHealth() { return heroHealth; }
	public int getMana() { return mana; }
	public int getMaximumMana() { return maximumMana; }
	public int getFatigue() { return fatigue; }
	public int getTurnsStarted() { return turnsStarted; }
	public List<Card> getHand() { return hand; }
	public List<Card> getDrawPile() { return drawPile; }
	public List<Card> getGraveyard() { return graveyard; }
	public int getRemainingMulligans() { return remainingMulligans; }
	public boolean isMulliganFinished() { return mulliganFinished; }

	/** @deprecated Hero health replaced round lives. */
	@Deprecated
	public int getLives() { return heroHealth; }

	/** @deprecated Passing was removed. */
	@Deprecated
	public boolean hasPassed() { return false; }

	PlayerState spendMana(int amount)
	{
		return copy(heroHealth, mana - amount, maximumMana, fatigue, turnsStarted,
			hand, drawPile, graveyard, remainingMulligans, mulliganFinished);
	}

	PlayerState damageHero(int amount)
	{
		return copy(Math.max(0, heroHealth - amount), mana, maximumMana, fatigue, turnsStarted,
			hand, drawPile, graveyard, remainingMulligans, mulliganFinished);
	}

	PlayerState removeFromHand(int index)
	{
		List<Card> nextHand = new ArrayList<>(hand);
		nextHand.remove(index);
		return copy(heroHealth, mana, maximumMana, fatigue, turnsStarted,
			nextHand, drawPile, graveyard, remainingMulligans, mulliganFinished);
	}

	PlayerState addToGraveyard(Card card)
	{
		List<Card> nextGraveyard = new ArrayList<>(graveyard);
		nextGraveyard.add(card);
		return copy(heroHealth, mana, maximumMana, fatigue, turnsStarted,
			hand, drawPile, nextGraveyard, remainingMulligans, mulliganFinished);
	}

	PlayerState startTurn(int handLimit)
	{
		int nextMaximum = turnsStarted == 0 ? maximumMana : Math.min(10, maximumMana + 1);
		PlayerState refreshed = copy(heroHealth, nextMaximum, nextMaximum, fatigue, turnsStarted + 1,
			hand, drawPile, graveyard, remainingMulligans, mulliganFinished);
		return refreshed.drawOne(handLimit);
	}

	PlayerState drawOne(int handLimit)
	{
		if (drawPile.isEmpty())
		{
			int nextFatigue = fatigue + 1;
			return copy(Math.max(0, heroHealth - nextFatigue), mana, maximumMana, nextFatigue, turnsStarted,
				hand, drawPile, graveyard, remainingMulligans, mulliganFinished);
		}
		Card drawn = drawPile.get(0);
		List<Card> nextDrawPile = new ArrayList<>(drawPile.subList(1, drawPile.size()));
		List<Card> nextHand = new ArrayList<>(hand);
		List<Card> nextGraveyard = new ArrayList<>(graveyard);
		if (nextHand.size() < handLimit) nextHand.add(drawn);
		else nextGraveyard.add(drawn);
		return copy(heroHealth, mana, maximumMana, fatigue, turnsStarted,
			nextHand, nextDrawPile, nextGraveyard, remainingMulligans, mulliganFinished);
	}

	PlayerState replaceOpeningCard(int handIndex)
	{
		List<Card> nextHand = new ArrayList<>(hand);
		Card replaced = nextHand.remove(handIndex);
		nextHand.add(drawPile.get(0));
		List<Card> nextDrawPile = new ArrayList<>(drawPile.subList(1, drawPile.size()));
		nextDrawPile.add(replaced);
		return copy(heroHealth, mana, maximumMana, fatigue, turnsStarted,
			nextHand, nextDrawPile, graveyard, remainingMulligans - 1, false);
	}

	PlayerState finishMulligan()
	{
		return copy(heroHealth, mana, maximumMana, fatigue, turnsStarted,
			hand, drawPile, graveyard, remainingMulligans, true);
	}

	private static PlayerState copy(int health, int mana, int maximumMana, int fatigue, int turnsStarted,
		List<? extends Card> hand, List<? extends Card> drawPile, List<? extends Card> graveyard,
		int remainingMulligans, boolean mulliganFinished)
	{
		return new PlayerState(health, mana, maximumMana, fatigue, turnsStarted, hand, drawPile, graveyard,
			remainingMulligans, mulliganFinished);
	}
}
