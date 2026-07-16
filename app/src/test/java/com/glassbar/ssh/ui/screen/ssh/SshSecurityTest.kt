package com.glassbar.ssh.ui.screen.ssh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSecurityTest {

    @Test
    fun `unknown host key always requires explicit trust`() {
        val presented = hostKey(keyBytes(seed = 1))

        val status = classifySshHostKey(trusted = null, presented = presented)

        assertTrue(status is SshHostKeyStatus.Unknown)
    }

    @Test
    fun `byte-identical trusted host key is accepted`() {
        val trusted = hostKey(keyBytes(seed = 2))
        val presented = trusted.copy(trustedAtEpochMillis = null)

        val status = classifySshHostKey(trusted, presented)

        assertTrue(status is SshHostKeyStatus.Trusted)
    }

    @Test
    fun `changed host key is never treated as trusted`() {
        val trusted = hostKey(keyBytes(seed = 3))
        val presented = hostKey(keyBytes(seed = 4)).copy(
            // Classification must compare full public-key bytes, not trust a
            // caller-provided fingerprint string.
            fingerprintSha256 = trusted.fingerprintSha256,
        )

        val status = classifySshHostKey(trusted, presented)

        assertTrue(status is SshHostKeyStatus.Changed)
        assertFalse(status is SshHostKeyStatus.Trusted)
    }

    @Test
    fun `host key algorithm and SHA256 fingerprint follow OpenSSH format`() {
        val bytes = keyBytes(seed = 5)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val expectedFingerprint = "SHA256:" + Base64.getEncoder()
            .withoutPadding()
            .encodeToString(expectedDigest)

        assertEquals("ssh-ed25519", algorithmOfSshHostKey(bytes))
        assertEquals(expectedFingerprint, fingerprintSshHostKey(bytes))
        assertArrayEquals(bytes, decodeSshHostKey(encodeSshHostKey(bytes)))
    }

    @Test
    fun `connection config owns and wipes private key material`() {
        val originalPrivateKey = byteArrayOf(1, 2, 3)
        val originalPublicKey = byteArrayOf(4, 5, 6)
        val originalPassphrase = byteArrayOf(7, 8, 9)
        val original = SshConfig(
            privateKey = originalPrivateKey,
            publicKey = originalPublicKey,
            privateKeyPassphrase = originalPassphrase,
        )

        val owned = original.copyWithOwnedSecrets()

        assertNotSame(originalPrivateKey, owned.privateKey)
        assertNotSame(originalPublicKey, owned.publicKey)
        assertNotSame(originalPassphrase, owned.privateKeyPassphrase)
        owned.clearOwnedSecrets()
        assertArrayEquals(byteArrayOf(0, 0, 0), owned.privateKey!!)
        assertArrayEquals(byteArrayOf(0, 0, 0), owned.publicKey!!)
        assertArrayEquals(byteArrayOf(0, 0, 0), owned.privateKeyPassphrase!!)
        assertArrayEquals(byteArrayOf(1, 2, 3), originalPrivateKey)
        assertArrayEquals(byteArrayOf(4, 5, 6), originalPublicKey)
        assertArrayEquals(byteArrayOf(7, 8, 9), originalPassphrase)
    }

    private fun hostKey(bytes: ByteArray): SshHostKey = SshHostKey(
        host = "example.com",
        port = 22,
        algorithm = algorithmOfSshHostKey(bytes),
        keyBase64 = encodeSshHostKey(bytes),
        fingerprintSha256 = fingerprintSshHostKey(bytes),
    )

    private fun keyBytes(seed: Int): ByteArray {
        val algorithm = "ssh-ed25519".toByteArray(StandardCharsets.US_ASCII)
        return ByteBuffer.allocate(Int.SIZE_BYTES + algorithm.size + 32)
            .putInt(algorithm.size)
            .put(algorithm)
            .put(ByteArray(32) { index -> (seed + index).toByte() })
            .array()
    }
}
