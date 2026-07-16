package com.glassbar.ssh.ui.screen.ssh

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputBufferTest {
    @Test
    fun `flushes in bounded ordered batches`() {
        val chunks = Collections.synchronizedList(mutableListOf<String>())
        val complete = CountDownLatch(1)
        val output = TerminalOutputBuffer(flushIntervalMs = 0, maxCharsPerFlush = 3) { text ->
            chunks += text
            if (chunks.sumOf(String::length) == 8) complete.countDown()
        }

        output.add("abcde")
        output.add("fgh")

        assertTrue(complete.await(2, TimeUnit.SECONDS))
        output.close()
        assertEquals("abcdefgh", chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 3 })
    }

    @Test
    fun `consumer exception is reported and later output continues`() {
        val errorSeen = CountDownLatch(1)
        val laterOutput = CountDownLatch(1)
        val output = TerminalOutputBuffer(
            flushIntervalMs = 0,
            maxCharsPerFlush = 3,
            onError = { errorSeen.countDown() },
        ) { text ->
            if (text == "bad") error("expected")
            if (text == "ok") laterOutput.countDown()
        }

        output.add("badok")

        assertTrue(errorSeen.await(2, TimeUnit.SECONDS))
        assertTrue(laterOutput.await(2, TimeUnit.SECONDS))
        output.close()
    }

    @Test
    fun `close drops delayed and future output`() {
        val called = CountDownLatch(1)
        val output = TerminalOutputBuffer(flushIntervalMs = 500) { called.countDown() }
        output.add("queued")

        output.close()
        output.add("ignored")

        assertFalse(called.await(700, TimeUnit.MILLISECONDS))
    }
}
