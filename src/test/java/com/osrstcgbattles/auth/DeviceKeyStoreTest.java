package com.osrstcgbattles.auth;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DeviceKeyStoreTest
{
	private Filepath temporaryDirectory;

	@Before
	public void createTemporaryDirectory() throws IOException
	{
		Filepath testDirectory = new TestPlugin().directory();
		testDirectory.createDirectories();
		temporaryDirectory = testDirectory.createTempDir("device-key-test-").rooted();
	}

	@After
	public void deleteTemporaryDirectory() throws IOException
	{
		if (temporaryDirectory != null && temporaryDirectory.exists())
		{
			temporaryDirectory.deleteRecursively();
		}
	}

	@Test
	public void createsPersistsAndReloadsSigningIdentity() throws Exception
	{
		DeviceKeyStore created = DeviceKeyStore.loadOrCreate(temporaryDirectory);
		DeviceKeyStore loaded = DeviceKeyStore.loadOrCreate(temporaryDirectory);

		assertEquals(created.getPublicKeyBase64Url(), loaded.getPublicKeyBase64Url());
		assertEquals(created.getFingerprint(), loaded.getFingerprint());
		assertTrue(temporaryDirectory.joinSegment(DeviceKeyStore.FILE_NAME).isFile());

		byte[] message = "canonical request bytes".getBytes(StandardCharsets.UTF_8);
		PublicKey publicKey = decodePublicKey(loaded.getPublicKeyBase64Url());
		Signature verifier = Signature.getInstance("SHA256withECDSA");
		verifier.initVerify(publicKey);
		verifier.update(message);
		assertTrue(verifier.verify(loaded.sign(message)));
	}

	@Test
	public void exposesFingerprintOfEncodedPublicKey() throws Exception
	{
		DeviceKeyStore store = DeviceKeyStore.loadOrCreate(temporaryDirectory);
		byte[] publicEncoding = Base64.getUrlDecoder().decode(store.getPublicKeyBase64Url());
		String expected = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicEncoding));

		assertEquals(expected, store.getFingerprint());
		assertFalse(store.getPublicKeyBase64Url().contains("="));
		assertFalse(store.getFingerprint().contains("="));
	}

	@Test
	public void rejectsCorruptExistingFileWithoutRotatingIt() throws Exception
	{
		Filepath keyFile = temporaryDirectory.joinSegment(DeviceKeyStore.FILE_NAME);
		byte[] corrupt = "not a device key".getBytes(StandardCharsets.US_ASCII);
		keyFile.write(corrupt);

		try
		{
			DeviceKeyStore.loadOrCreate(temporaryDirectory);
			fail("Expected corrupt key file to fail");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("device key") || expected.getMessage().contains("Device key"));
		}

		byte[] after = new byte[corrupt.length];
		try (java.io.InputStream input = keyFile.openInputStream())
		{
			int offset = 0;
			while (offset < after.length)
			{
				int count = input.read(after, offset, after.length - offset);
				if (count < 0)
				{
					break;
				}
				offset += count;
			}
		}
		assertArrayEquals(corrupt, after);
	}

	@Test
	public void rejectsOversizedExistingFile() throws Exception
	{
		Filepath keyFile = temporaryDirectory.joinSegment(DeviceKeyStore.FILE_NAME);
		byte[] oversized = new byte[2049];
		Arrays.fill(oversized, (byte) 1);
		keyFile.write(oversized);

		try
		{
			DeviceKeyStore.loadOrCreate(temporaryDirectory);
			fail("Expected oversized key file to fail");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("size"));
		}
		assertEquals(oversized.length, keyFile.size());
	}

	@Test
	public void rejectsValidEcKeysFromAnyCurveOtherThanP256() throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp384r1"));
		writeKeyPair(generator.generateKeyPair());

		try
		{
			DeviceKeyStore.loadOrCreate(temporaryDirectory);
			fail("Expected a non-P-256 key to fail");
		}
		catch (GeneralSecurityException expected)
		{
			assertTrue(expected.getMessage().contains("P-256"));
		}
	}

	@Test
	public void signaturesDoNotVerifyForChangedBytes() throws Exception
	{
		DeviceKeyStore store = DeviceKeyStore.loadOrCreate(temporaryDirectory);
		byte[] signature = store.sign("one".getBytes(StandardCharsets.US_ASCII));
		Signature verifier = Signature.getInstance("SHA256withECDSA");
		verifier.initVerify(decodePublicKey(store.getPublicKeyBase64Url()));
		verifier.update("two".getBytes(StandardCharsets.US_ASCII));

		assertFalse(verifier.verify(signature));
	}

	private static PublicKey decodePublicKey(String encoded) throws Exception
	{
		return KeyFactory.getInstance("EC").generatePublic(
			new X509EncodedKeySpec(Base64.getUrlDecoder().decode(encoded)));
	}

	private void writeKeyPair(KeyPair keyPair) throws IOException
	{
		byte[] privateKey = keyPair.getPrivate().getEncoded();
		byte[] publicKey = keyPair.getPublic().getEncoded();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes))
		{
			output.write(new byte[]{'O', 'S', 'R', 'S', 'T', 'C', 'G', 'K'});
			output.writeInt(1);
			output.writeInt(privateKey.length);
			output.write(privateKey);
			output.writeInt(publicKey.length);
			output.write(publicKey);
		}
		temporaryDirectory.joinSegment(DeviceKeyStore.FILE_NAME).write(bytes.toByteArray());
	}

	@PluginDescriptor(name = "Device key store tests", internalName = "device-key-store-tests")
	private static final class TestPlugin extends Plugin
	{
		private Filepath directory() throws IOException
		{
			return getPluginDirectory();
		}
	}
}
