package com.osrstcgbattles.engine;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class StateHasher
{
	private StateHasher() { }

	public static String publicStateSha256(MatchState state) { return stateSha256(state, false); }
	public static String synchronizationStateSha256(MatchState state) { return stateSha256(state, true); }

	private static String stateSha256(MatchState state, boolean synchronization)
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeUTF(synchronization ? "osrs-tcg-hearthstone-lite-sync-v3"
				: "osrs-tcg-hearthstone-lite-public-v3");
			writePublicState(out, state);
			if (synchronization)
			{
				for (PlayerId player : PlayerId.values())
				{
					writeCards(out, state.getPlayer(player).getHand());
					writeCards(out, state.getPlayer(player).getDrawPile());
				}
			}
			out.flush();
			return sha256Hex(bytes.toByteArray());
		}
		catch (IOException exception)
		{
			throw new IllegalStateException("Could not serialize match state", exception);
		}
	}

	private static void writePublicState(DataOutputStream out, MatchState state) throws IOException
	{
		out.writeInt(DuelscapeEngine.RULESET_VERSION);
		out.writeInt(state.getTurnNumber());
		out.writeUTF(state.getStartingPlayer().name());
		out.writeUTF(state.getStatus().name());
		out.writeUTF(state.getPhase().name());
		out.writeUTF(state.getActivePlayer().map(Enum::name).orElse(""));
		out.writeUTF(state.getWinner().map(Enum::name).orElse(""));
		out.writeLong(state.getNextUnitInstanceId());
		for (PlayerId player : PlayerId.values())
		{
			PlayerState value = state.getPlayer(player);
			out.writeUTF(player.name());
			out.writeInt(value.getHeroHealth());
			out.writeInt(value.getMana());
			out.writeInt(value.getTemporaryMana());
			out.writeInt(value.getMaximumMana());
			out.writeInt(value.getFatigue());
			out.writeInt(value.getTurnsStarted());
			out.writeInt(value.getRemainingMulligans());
			out.writeBoolean(value.isMulliganFinished());
			out.writeInt(value.getHand().size());
			out.writeInt(value.getDrawPile().size());
			writeCards(out, value.getGraveyard());
			writeBoardUnits(out, state.getBoard().getUnits(player));
		}
	}

	private static void writeCards(DataOutputStream out, Iterable<? extends Card> cards) throws IOException
	{
		int count = 0;
		for (Card ignored : cards) count++;
		out.writeInt(count);
		for (Card card : cards) writeCard(out, card);
	}

	private static void writeBoardUnits(DataOutputStream out, Iterable<BoardUnit> units) throws IOException
	{
		int count = 0;
		for (BoardUnit ignored : units) count++;
		out.writeInt(count);
		for (BoardUnit unit : units)
		{
			out.writeUTF(unit.getInstanceId());
			out.writeInt(unit.getCurrentAttack());
			out.writeInt(unit.getCurrentHealth());
			out.writeBoolean(unit.isReady());
			out.writeBoolean(unit.isShielded());
			out.writeBoolean(unit.isStealthed());
			out.writeBoolean(unit.isRushRestricted());
			writeCard(out, unit.getDefinition());
		}
	}

	private static void writeCard(DataOutputStream out, Card card) throws IOException
	{
		if (!(card instanceof UnitCard) && !(card instanceof SpecialCard))
		{
			throw new IllegalArgumentException("unsupported card type");
		}
		out.writeUTF(card instanceof UnitCard ? "UNIT" : "SPECIAL");
		out.writeUTF(card.getId());
		out.writeUTF(card.getName());
		out.writeInt(card.getManaCost());
		List<DeployEffect> effects;
		if (card instanceof UnitCard)
		{
			UnitCard unit = (UnitCard) card;
			out.writeInt(unit.getBaseAttack());
			out.writeInt(unit.getBaseHealth());
			effects = unit.getDeployEffects();
			out.writeInt(unit.getKeywords().size());
			for (UnitKeyword keyword : unit.getKeywords()) out.writeUTF(keyword.name());
			out.writeInt(unit.getDeathrattles().size());
			for (DeathrattleEffect effect : unit.getDeathrattles())
			{
				out.writeUTF(effect.getType().name());
				out.writeInt(effect.getAmount());
			}
		}
		else
		{
			effects = ((SpecialCard) card).getDeployEffects();
			out.writeInt(0);
			out.writeInt(0);
		}
		out.writeInt(effects.size());
		for (DeployEffect effect : effects)
		{
			out.writeUTF(effect.getType().name());
			out.writeUTF(effect.getTarget().name());
			out.writeInt(effect.getAmount());
		}
	}

	private static String sha256Hex(byte[] value)
	{
		try
		{
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte item : digest)
			{
				hex.append(Character.forDigit((item >>> 4) & 0xf, 16));
				hex.append(Character.forDigit(item & 0xf, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
