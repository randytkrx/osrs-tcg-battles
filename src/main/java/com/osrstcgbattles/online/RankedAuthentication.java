package com.osrstcgbattles.online;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Deterministic bytes signed for ranked device-key authentication. */
public final class RankedAuthentication
{
	private static final byte[] DOMAIN = "duelscape-ranked-auth-v1".getBytes(StandardCharsets.US_ASCII);

	private RankedAuthentication() {}

	public static byte[] challenge(String audience, String challengeId, byte[] nonce, String ign, byte[] publicKey)
	{
		Objects.requireNonNull(audience, "audience");
		Objects.requireNonNull(challengeId, "challengeId");
		Objects.requireNonNull(nonce, "nonce");
		Objects.requireNonNull(ign, "ign");
		Objects.requireNonNull(publicKey, "publicKey");
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes))
			{
				write(output, DOMAIN);
				write(output, audience.getBytes(StandardCharsets.UTF_8));
				write(output, challengeId.getBytes(StandardCharsets.UTF_8));
				write(output, nonce);
				write(output, ign.getBytes(StandardCharsets.UTF_8));
				write(output, publicKey);
			}
			return bytes.toByteArray();
		}
		catch (IOException impossible)
		{
			throw new IllegalStateException("Could not encode authentication challenge", impossible);
		}
	}

	private static void write(DataOutputStream output, byte[] value) throws IOException
	{
		output.writeInt(value.length);
		output.write(value);
	}
}
