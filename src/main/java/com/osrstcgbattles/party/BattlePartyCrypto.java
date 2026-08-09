package com.osrstcgbattles.party;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class BattlePartyCrypto
{
	private static final int NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final int AES_KEY_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private BattlePartyCrypto()
	{
	}

	public static KeyPair generateEphemeralKeyPair() throws GeneralSecurityException
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
		return generator.generateKeyPair();
	}

	public static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException
	{
		if (encoded == null || encoded.length < 64 || encoded.length > 256)
		{
			throw new GeneralSecurityException("public key has an invalid length");
		}
		PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
		if (!(key instanceof ECPublicKey) || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256)
		{
			throw new GeneralSecurityException("public key is not P-256");
		}
		return key;
	}

	public static byte[] deriveSessionKey(PrivateKey privateKey, PublicKey peerPublicKey, byte[] salt,
		String matchId, long firstMemberId, long secondMemberId) throws GeneralSecurityException
	{
		if (privateKey == null || peerPublicKey == null || salt == null || salt.length < 16 || salt.length > 64)
		{
			throw new GeneralSecurityException("invalid key agreement input");
		}
		if (!(privateKey instanceof ECPrivateKey) || !(peerPublicKey instanceof ECPublicKey)
			|| ((ECPrivateKey) privateKey).getParams().getCurve().getField().getFieldSize() != 256
			|| ((ECPublicKey) peerPublicKey).getParams().getCurve().getField().getFieldSize() != 256)
		{
			throw new GeneralSecurityException("key agreement requires P-256 keys");
		}
		if (matchId == null || matchId.isEmpty() || matchId.length() > BattlePartyEnvelope.MAX_ID_LENGTH
			|| firstMemberId <= 0 || secondMemberId <= 0 || firstMemberId == secondMemberId)
		{
			throw new GeneralSecurityException("invalid key derivation context");
		}
		KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
		agreement.init(privateKey);
		agreement.doPhase(peerPublicKey, true);
		byte[] secret = agreement.generateSecret();
		try
		{
			long low = Math.min(firstMemberId, secondMemberId);
			long high = Math.max(firstMemberId, secondMemberId);
			byte[] info = fields("osrs-tcg-battle-v1", matchId, Long.toString(low), Long.toString(high));
			return hkdfSha256(secret, salt, info, AES_KEY_BYTES);
		}
		finally
		{
			Arrays.fill(secret, (byte) 0);
		}
	}

	public static String authenticationCode(byte[] sessionKey, String matchId, long firstMemberId,
		long secondMemberId) throws GeneralSecurityException
	{
		if (sessionKey == null || sessionKey.length != AES_KEY_BYTES || matchId == null || matchId.isEmpty())
		{
			throw new GeneralSecurityException("invalid authentication-code input");
		}
		long low = Math.min(firstMemberId, secondMemberId);
		long high = Math.max(firstMemberId, secondMemberId);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(sessionKey, "HmacSHA256"));
		byte[] digest = mac.doFinal(fields("osrs-tcg-auth-v1", matchId,
			Long.toString(low), Long.toString(high)));
		int value = ((digest[0] & 0xff) << 16 | (digest[1] & 0xff) << 8 | (digest[2] & 0xff)) % 1_000_000;
		return String.format("%03d-%03d", value / 1000, value % 1000);
	}

	/** Combines both contributed salts without making either peer the protocol leader. */
	public static byte[] combineHandshakeSalts(byte[] localSalt, long localMemberId, byte[] peerSalt,
		long peerMemberId) throws GeneralSecurityException
	{
		if (localSalt == null || peerSalt == null || localSalt.length < 16 || localSalt.length > 64
			|| peerSalt.length < 16 || peerSalt.length > 64 || localMemberId <= 0 || peerMemberId <= 0
			|| localMemberId == peerMemberId)
		{
			throw new GeneralSecurityException("invalid handshake salts");
		}
		byte[] lowSalt = localMemberId < peerMemberId ? localSalt : peerSalt;
		byte[] highSalt = localMemberId < peerMemberId ? peerSalt : localSalt;
		try
		{
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			digest.update(fields("osrs-tcg-battle-salts-v1", Long.toString(Math.min(localMemberId, peerMemberId)),
				Long.toString(Math.max(localMemberId, peerMemberId))));
			digest.update(lowSalt);
			digest.update(highSalt);
			return digest.digest();
		}
		catch (GeneralSecurityException exception)
		{
			throw exception;
		}
	}

	public static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length)
		throws GeneralSecurityException
	{
		if (inputKeyMaterial == null || inputKeyMaterial.length == 0 || length <= 0 || length > 255 * 32)
		{
			throw new GeneralSecurityException("invalid HKDF input");
		}
		Mac mac = Mac.getInstance("HmacSHA256");
		byte[] actualSalt = salt == null ? new byte[32] : salt;
		mac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
		byte[] pseudoRandomKey = mac.doFinal(inputKeyMaterial);
		byte[] output = new byte[length];
		byte[] previous = new byte[0];
		int offset = 0;
		try
		{
			for (int counter = 1; offset < length; counter++)
			{
				mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
				mac.update(previous);
				if (info != null)
				{
					mac.update(info);
				}
				mac.update((byte) counter);
				previous = mac.doFinal();
				int copied = Math.min(previous.length, length - offset);
				System.arraycopy(previous, 0, output, offset, copied);
				offset += copied;
			}
			return output;
		}
		finally
		{
			Arrays.fill(pseudoRandomKey, (byte) 0);
			Arrays.fill(previous, (byte) 0);
		}
	}

	public static EncryptedPartyPayload encrypt(byte[] key, byte[] plaintext, BattlePartyEnvelope envelope,
		long senderMemberId) throws GeneralSecurityException
	{
		validateEncryptionInput(key, plaintext, envelope);
		byte[] nonce = new byte[NONCE_BYTES];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
		cipher.updateAAD(authenticatedData(envelope, senderMemberId));
		return new EncryptedPartyPayload(nonce, cipher.doFinal(plaintext));
	}

	public static byte[] decrypt(byte[] key, EncryptedPartyPayload encrypted, BattlePartyEnvelope envelope,
		long senderMemberId) throws GeneralSecurityException
	{
		if (encrypted == null || encrypted.getNonce().length != NONCE_BYTES
			|| encrypted.getCiphertext().length < 16
			|| encrypted.getCiphertext().length > BattlePartyEnvelope.MAX_CIPHERTEXT_BYTES)
		{
			throw new GeneralSecurityException("invalid encrypted payload");
		}
		validateEncryptionInput(key, new byte[0], envelope);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
			new GCMParameterSpec(GCM_TAG_BITS, encrypted.getNonce()));
		cipher.updateAAD(authenticatedData(envelope, senderMemberId));
		try
		{
			return cipher.doFinal(encrypted.getCiphertext());
		}
		catch (AEADBadTagException exception)
		{
			throw exception;
		}
	}

	public static byte[] authenticatedData(BattlePartyEnvelope envelope, long senderMemberId)
		throws GeneralSecurityException
	{
		if (envelope == null || envelope.getMessageId() == null || envelope.getMatchId() == null
			|| envelope.getMessageType() == null || senderMemberId <= 0 || envelope.getRecipientMemberId() <= 0
			|| envelope.getSequence() <= 0 || envelope.getAckSequence() < 0)
		{
			throw new GeneralSecurityException("invalid authenticated metadata");
		}
		return fields(Integer.toString(envelope.getProtocolVersion()), envelope.getMessageId(), envelope.getMatchId(),
			Long.toString(senderMemberId), Long.toString(envelope.getRecipientMemberId()),
			Long.toString(envelope.getSequence()), Long.toString(envelope.getAckSequence()),
			envelope.getMessageType().name());
	}

	private static void validateEncryptionInput(byte[] key, byte[] plaintext, BattlePartyEnvelope envelope)
		throws GeneralSecurityException
	{
		if (key == null || key.length != AES_KEY_BYTES || plaintext == null
			|| plaintext.length > BattlePartyEnvelope.MAX_CIPHERTEXT_BYTES - 16 || envelope == null)
		{
			throw new GeneralSecurityException("invalid encryption input");
		}
	}

	private static byte[] fields(String... values) throws GeneralSecurityException
	{
		try
		{
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream output = new DataOutputStream(bytes);
			for (String value : values)
			{
				if (value == null)
				{
					throw new GeneralSecurityException("null context field");
				}
				byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
				output.writeInt(encoded.length);
				output.write(encoded);
			}
			return bytes.toByteArray();
		}
		catch (IOException impossible)
		{
			throw new GeneralSecurityException(impossible);
		}
	}
}
