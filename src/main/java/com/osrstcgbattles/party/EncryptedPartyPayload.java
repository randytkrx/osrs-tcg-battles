package com.osrstcgbattles.party;

import java.util.Arrays;

public final class EncryptedPartyPayload
{
	private final byte[] nonce;
	private final byte[] ciphertext;

	public EncryptedPartyPayload(byte[] nonce, byte[] ciphertext)
	{
		this.nonce = Arrays.copyOf(nonce, nonce.length);
		this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
	}

	public byte[] getNonce()
	{
		return Arrays.copyOf(nonce, nonce.length);
	}

	public byte[] getCiphertext()
	{
		return Arrays.copyOf(ciphertext, ciphertext.length);
	}
}
