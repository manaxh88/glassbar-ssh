package com.glassbar.ssh.ui.screen.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConnectionStoreTest {

    @Test
    fun `missing stored data decodes as empty`() {
        val result = decodeConnectionData<String>(null) {
            error("decoder should not be called")
        }

        assertSame(ConnectionDataDecodeResult.Empty, result)
    }

    @Test
    fun `valid stored data returns decoder value`() {
        val result = decodeConnectionData("stored-json") { raw -> raw.length }

        assertTrue(result is ConnectionDataDecodeResult.Success)
        assertEquals(11, (result as ConnectionDataDecodeResult.Success).value)
    }

    @Test
    fun `decode failure preserves exact corrupt raw value`() {
        val raw = "[{broken-json-and-sensitive-fields}]"
        val failure = IllegalArgumentException("invalid JSON")

        val result = decodeConnectionData(raw) { throw failure }

        assertTrue(result is ConnectionDataDecodeResult.Corrupt)
        result as ConnectionDataDecodeResult.Corrupt
        assertEquals(raw, result.raw)
        assertSame(failure, result.cause)
    }

    @Test
    fun `successful decoder may return null without being treated as corruption`() {
        val result = decodeConnectionData<String?>("null") { null }

        assertTrue(result is ConnectionDataDecodeResult.Success)
        assertNull((result as ConnectionDataDecodeResult.Success).value)
    }

    @Test
    fun `persisted corruption blocks writes after process restart`() {
        assertTrue(
            shouldBlockConnectionStoreWrites(
                persistedCorruption = true,
                runtimeLoadError = null,
            ),
        )
    }

    @Test
    fun `runtime load failure blocks writes when backup commit fails`() {
        val error = SshConnectionLoadError(
            occurredAtMillis = 1234L,
            detail = "Unable to decode stored SSH connections",
            backupAvailable = false,
        )

        assertTrue(
            shouldBlockConnectionStoreWrites(
                persistedCorruption = false,
                runtimeLoadError = error,
            ),
        )
    }
}
