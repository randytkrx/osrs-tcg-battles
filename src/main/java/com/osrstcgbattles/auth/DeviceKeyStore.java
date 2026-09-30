package com.osrstcgbattles.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import net.runelite.client.util.Filepath;

/** A persistent P-256 signing identity scoped to a RuneLite plugin directory. */
public final class DeviceKeyStore
{
	static final String FILE_NAME = "device-key-v1.bin";

	private static final byte[] MAGIC = {'O', 'S', 'R', 'S', 'T', 'C', 'G', 'K'};
	private static final int VERSION = 1;
	private static final int MAX_KEY_ENCODING_BYTES = 512;
	private static final int MAX_FILE_BYTES = 2 * MAX_KEY_ENCODING_BYTES + 32;
	private static final byte[] SELF_TEST_MESSAGE = "osrs-tcg-device-key-self-test-v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	private static final Object LOAD_LOCK = new Object();

	private final PrivateKey privateKey;
	private final String publicKeyBase64Url;
	private final String fingerprint;

	private DeviceKeyStore(PrivateKey privateKey, PublicKey publicKey) throws GeneralSecurityException
	{
		this.privateKey = privateKey;
		byte[] encodedPublicKey = publicKey.getEncoded();
		this.publicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(encodedPublicKey);
		this.fingerprint = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
	}

	/**
	 * Loads the device key, creating it only when the key file does not exist.
	 * Existing invalid data is reported and is never replaced.
	 */
	public static DeviceKeyStore loadOrCreate(Filepath pluginDirectory) throws IOException, GeneralSecurityException
	{
		Objects.requireNonNull(pluginDirectory, "pluginDirectory");
		synchronized (LOAD_LOCK)
		{
			pluginDirectory.createDirectories();
			Filepath keyFile = pluginDirectory.joinSegment(FILE_NAME);
			if (keyFile.exists())
			{
				return load(keyFile);
			}

			KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
			generator.initialize(new ECGenParameterSpec("secp256r1"));
			KeyPair keyPair = generator.generateKeyPair();
			DeviceKeyStore store = validated(keyPair.getPrivate(), keyPair.getPublic());
			persistAtomically(pluginDirectory, keyFile, encode(keyPair));
			return store;
		}
	}

	public String getPublicKeyBase64Url()
	{
		return publicKeyBase64Url;
	}

	/** Returns the unpadded base64url SHA-256 fingerprint of the X.509 public key encoding. */
	public String getFingerprint()
	{
		return fingerprint;
	}

	/** Signs the supplied bytes with SHA256withECDSA. */
	public byte[] sign(byte[] message) throws GeneralSecurityException
	{
		Objects.requireNonNull(message, "message");
		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(privateKey);
		signer.update(message);
		return signer.sign();
	}

	private static DeviceKeyStore load(Filepath keyFile) throws IOException, GeneralSecurityException
	{
		if (!keyFile.isFile())
		{
			throw new IOException("Device key path is not a regular file");
		}
		long size = keyFile.size();
		if (size <= 0 || size > MAX_FILE_BYTES)
		{
			throw new IOException("Device key file has an invalid size");
		}

		byte[] bytes = new byte[(int) size];
		try (DataInputStream input = new DataInputStream(keyFile.openInputStream()))
		{
			input.readFully(bytes);
			if (input.read() != -1)
			{
				throw new IOException("Device key file changed while being read");
			}
		}

		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes)))
		{
			byte[] magic = new byte[MAGIC.length];
			input.readFully(magic);
			if (!Arrays.equals(MAGIC, magic) || input.readInt() != VERSION)
			{
				throw new IOException("Unsupported device key format");
			}
			byte[] privateEncoding = readEncoding(input, "private");
			byte[] publicEncoding = readEncoding(input, "public");
			if (input.read() != -1)
			{
				throw new IOException("Trailing data in device key file");
			}

			KeyFactory keyFactory = KeyFactory.getInstance("EC");
			PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoding));
			PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicEncoding));
			return validated(privateKey, publicKey);
		}
		catch (EOFException | IllegalArgumentException error)
		{
			throw new IOException("Malformed device key file", error);
		}
	}

	private static byte[] readEncoding(DataInputStream input, String name) throws IOException
	{
		int length = input.readInt();
		if (length <= 0 || length > MAX_KEY_ENCODING_BYTES)
		{
			throw new IOException("Invalid " + name + " key encoding length");
		}
		byte[] encoding = new byte[length];
		input.readFully(encoding);
		return encoding;
	}

	private static byte[] encode(KeyPair keyPair) throws IOException
	{
		byte[] privateEncoding = keyPair.getPrivate().getEncoded();
		byte[] publicEncoding = keyPair.getPublic().getEncoded();
		if (!"PKCS#8".equals(keyPair.getPrivate().getFormat())
			|| !"X.509".equals(keyPair.getPublic().getFormat())
			|| privateEncoding == null || publicEncoding == null
			|| privateEncoding.length > MAX_KEY_ENCODING_BYTES || publicEncoding.length > MAX_KEY_ENCODING_BYTES)
		{
			throw new IOException("Key provider returned an invalid encoding");
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes))
		{
			output.write(MAGIC);
			output.writeInt(VERSION);
			output.writeInt(privateEncoding.length);
			output.write(privateEncoding);
			output.writeInt(publicEncoding.length);
			output.write(publicEncoding);
		}
		return bytes.toByteArray();
	}

	private static void persistAtomically(Filepath directory, Filepath keyFile, byte[] bytes) throws IOException
	{
		Filepath temporary = directory.createTempFile("device-key-", ".tmp");
		boolean moved = false;
		try
		{
			try (FileChannel channel = temporary.openFileChannel(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining())
				{
					channel.write(buffer);
				}
				channel.force(true);
			}
			try
			{
				temporary.moveTo(keyFile, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException exception)
			{
				temporary.moveTo(keyFile);
			}
			moved = true;
		}
		finally
		{
			if (!moved)
			{
				temporary.deleteIfExists();
			}
		}
	}

	private static DeviceKeyStore validated(PrivateKey privateKey, PublicKey publicKey)
		throws GeneralSecurityException
	{
		if (!(privateKey instanceof ECPrivateKey) || !(publicKey instanceof ECPublicKey)
			|| !"EC".equals(privateKey.getAlgorithm()) || !"EC".equals(publicKey.getAlgorithm())
			|| !"PKCS#8".equals(privateKey.getFormat()) || !"X.509".equals(publicKey.getFormat()))
		{
			throw new GeneralSecurityException("Device key is not an EC key pair");
		}

		ECParameterSpec expected = expectedParameters();
		ECPrivateKey ecPrivateKey = (ECPrivateKey) privateKey;
		ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
		if (!sameParameters(expected, ecPrivateKey.getParams()) || !sameParameters(expected, ecPublicKey.getParams())
			|| ecPrivateKey.getS().signum() <= 0 || ecPrivateKey.getS().compareTo(expected.getOrder()) >= 0
			|| !validPoint(ecPublicKey.getW(), expected))
		{
			throw new GeneralSecurityException("Device key is not a valid P-256 key pair");
		}

		Signature signature = Signature.getInstance("SHA256withECDSA");
		signature.initSign(privateKey);
		signature.update(SELF_TEST_MESSAGE);
		byte[] proof = signature.sign();
		signature.initVerify(publicKey);
		signature.update(SELF_TEST_MESSAGE);
		if (!signature.verify(proof))
		{
			throw new GeneralSecurityException("Device private and public keys do not match");
		}
		return new DeviceKeyStore(privateKey, publicKey);
	}

	private static ECParameterSpec expectedParameters() throws GeneralSecurityException
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return ((ECKey) generator.generateKeyPair().getPublic()).getParams();
	}

	private static boolean sameParameters(ECParameterSpec left, ECParameterSpec right)
	{
		return right != null
			&& left.getCurve().equals(right.getCurve())
			&& left.getGenerator().equals(right.getGenerator())
			&& left.getOrder().equals(right.getOrder())
			&& left.getCofactor() == right.getCofactor();
	}

	private static boolean validPoint(ECPoint point, ECParameterSpec parameters)
	{
		if (point == null || ECPoint.POINT_INFINITY.equals(point)
			|| !(parameters.getCurve().getField() instanceof ECFieldFp))
		{
			return false;
		}
		java.math.BigInteger modulus = ((ECFieldFp) parameters.getCurve().getField()).getP();
		java.math.BigInteger x = point.getAffineX();
		java.math.BigInteger y = point.getAffineY();
		if (x.signum() < 0 || y.signum() < 0 || x.compareTo(modulus) >= 0 || y.compareTo(modulus) >= 0)
		{
			return false;
		}
		java.math.BigInteger left = y.multiply(y).mod(modulus);
		java.math.BigInteger right = x.multiply(x).multiply(x)
			.add(parameters.getCurve().getA().multiply(x))
			.add(parameters.getCurve().getB()).mod(modulus);
		return left.equals(right);
	}
}
