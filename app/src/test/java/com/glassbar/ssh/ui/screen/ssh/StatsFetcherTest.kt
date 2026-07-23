package com.glassbar.ssh.ui.screen.ssh

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StatsFetcherTest {

    @Test
    fun `parse accepts whitespace and ignores unrelated output`() {
        val stats = StatsFetcher.parse(
            output = """
                login banner
                  CPU=12.50

                OTHER=value
                MEM=67.25
            """.trimIndent(),
            updatedAt = 1234L,
        )

        assertEquals(12.5f, stats.cpuPercent, 0f)
        assertEquals(67.25f, stats.memPercent, 0f)
        assertNull(stats.error)
        assertEquals(1234L, stats.updatedAtMillis)
    }

    @Test
    fun `parse clamps percentages to their valid range`() {
        val stats = StatsFetcher.parse(
            output = "CPU=-4.5\r\nMEM=130.25\r\n",
            updatedAt = 99L,
        )

        assertEquals(0f, stats.cpuPercent, 0f)
        assertEquals(100f, stats.memPercent, 0f)
    }

    @Test
    fun `parse rejects missing CPU value`() {
        val error = assertThrows(StatsParseException::class.java) {
            StatsFetcher.parse("MEM=20.0", updatedAt = 1L)
        }

        assertEquals("Stats output missing field: CPU", error.message)
    }

    @Test
    fun `parse rejects malformed memory value`() {
        val error = assertThrows(StatsParseException::class.java) {
            StatsFetcher.parse("CPU=20.0\nMEM=unavailable", updatedAt = 1L)
        }

        assertEquals("Stats output missing field: MEM", error.message)
    }

    @Test
    fun `parse rejects non-finite percentages`() {
        assertThrows(StatsParseException::class.java) {
            StatsFetcher.parse("CPU=NaN\nMEM=20.0", updatedAt = 1L)
        }
        assertThrows(StatsParseException::class.java) {
            StatsFetcher.parse("CPU=20.0\nMEM=Infinity", updatedAt = 1L)
        }
    }
}
