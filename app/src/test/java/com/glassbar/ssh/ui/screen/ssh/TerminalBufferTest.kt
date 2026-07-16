package com.glassbar.ssh.ui.screen.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun `SGR applies standard and indexed colors then reset restores defaults`() {
        val buffer = TerminalBuffer(rows = 2, cols = 8)

        buffer.write("\u001B[31;44;1;4;7mA\u001B[38;5;196;48;5;236;22;24;27mB\u001B[0mC")

        val row = buffer.visibleRows().first()
        assertEquals(1, row[0].fg)
        assertEquals(4, row[0].bg)
        assertTrue(row[0].bold)
        assertTrue(row[0].underline)
        assertTrue(row[0].inverse)

        assertEquals(196, row[1].fg)
        assertEquals(236, row[1].bg)
        assertFalse(row[1].bold)
        assertFalse(row[1].underline)
        assertFalse(row[1].inverse)

        assertEquals(TerminalColors.DEFAULT_FG, row[2].fg)
        assertEquals(TerminalColors.DEFAULT_BG, row[2].bg)
        assertEquals(0xFFFF0000.toInt(), TerminalColors.fg(196))
        assertEquals(0xFF303030.toInt(), TerminalColors.bg(236))
    }

    @Test
    fun `wide characters occupy two cells and wrap before a partial cell`() {
        val buffer = TerminalBuffer(rows = 2, cols = 5)

        buffer.write("ab\u4F60\u597D")

        val rows = buffer.visibleRows()
        assertEquals('a', rows[0][0].char)
        assertEquals('b', rows[0][1].char)
        assertEquals('\u4F60', rows[0][2].char)
        assertTrue(rows[0][3].wideContinuation)
        assertEquals('\u597D', rows[1][0].char)
        assertTrue(rows[1][1].wideContinuation)
        assertEquals(1, buffer.cursorRow)
        assertEquals(2, buffer.cursorCol)
    }

    @Test
    fun `scrollback ring drops only oldest lines and keeps live screen`() {
        val buffer = TerminalBuffer(rows = 2, cols = 8, scrollbackLines = 3)

        repeat(7) { line -> buffer.write("line$line\r\n") }

        assertEquals(3, buffer.snapshot().screenTop)
        buffer.scrollBy(3)
        assertEquals(listOf("line3", "line4"), buffer.renderedLines())

        buffer.scrollBy(-1)
        assertEquals(listOf("line4", "line5"), buffer.renderedLines())

        buffer.scrollToBottom()
        assertEquals(listOf("line6", ""), buffer.renderedLines())
    }

    @Test
    fun `resize retains scrollback and active lines`() {
        val buffer = TerminalBuffer(rows = 3, cols = 8, scrollbackLines = 4)
        buffer.write((0..5).joinToString("\r\n") { "line$it" })
        val allLines = TerminalSelection(
            start = TerminalPosition(row = 0, col = 0),
            end = TerminalPosition(row = 5, col = 7),
        )

        assertEquals((0..5).joinToString("\n") { "line$it" }, buffer.textInRange(allLines))

        buffer.resize(newRows = 2, newCols = 6)
        assertEquals(2, buffer.rows)
        assertEquals(6, buffer.cols)
        assertEquals((0..5).joinToString("\n") { "line$it" }, buffer.textInRange(allLines))

        buffer.resize(newRows = 4, newCols = 10)
        assertEquals(4, buffer.rows)
        assertEquals(10, buffer.cols)
        assertEquals((0..5).joinToString("\n") { "line$it" }, buffer.textInRange(allLines))
    }

    @Test
    fun `selection normalizes endpoints trims padding and skips wide continuations`() {
        val buffer = TerminalBuffer(rows = 3, cols = 10)
        buffer.write("ab\u4F60cd\r\nsecond")

        val selected = buffer.textInRange(
            TerminalSelection(
                start = TerminalPosition(row = 1, col = 5),
                end = TerminalPosition(row = 0, col = 1),
            ),
        )

        assertEquals("b\u4F60cd\nsecond", selected)
    }

    @Test
    fun `erase commands handle pending wrap and preserve cursor position`() {
        val buffer = TerminalBuffer(rows = 2, cols = 3)
        buffer.write("abc")
        assertEquals(3, buffer.cursorCol)

        buffer.write("\u001B[1K")
        assertEquals("", buffer.renderedLines()[0])
        assertEquals(3, buffer.cursorCol)

        buffer.write("d\u001B[2J")
        assertEquals(listOf("", ""), buffer.renderedLines())
        assertEquals(1, buffer.cursorRow)
        assertEquals(1, buffer.cursorCol)
    }

    @Test
    fun `OSC handles empty ST and bounds untrusted payloads`() {
        val buffer = TerminalBuffer(rows = 2, cols = 8)
        buffer.write("\u001B]0;ok\u0007")
        assertEquals("ok", buffer.getTitle())

        buffer.write("\u001B]0;\u001B\\A")
        assertEquals('A', buffer.visibleRows()[0][0].char)
        assertEquals("", buffer.getTitle())

        buffer.write("\u001B]0;${"x".repeat(5000)}\u0007B")
        assertEquals('B', buffer.visibleRows()[0][1].char)
        assertEquals("", buffer.getTitle())

        buffer.write("\u001B[${"9".repeat(100)}mC")
        assertEquals('C', buffer.visibleRows()[0][2].char)
    }

    @Test
    fun `SGR supports true color and trailing empty reset`() {
        val buffer = TerminalBuffer(rows = 2, cols = 8)
        buffer.write("\u001B[38;2;12;34;56;48;2;78;90;123mA\u001B[31;mB")

        val row = buffer.visibleRows()[0]
        assertEquals(0xFF0C2238.toInt(), TerminalColors.fg(row[0].fg))
        assertEquals(0xFF4E5A7B.toInt(), TerminalColors.bg(row[0].bg))
        assertEquals(TerminalColors.DEFAULT_FG, row[1].fg)
        assertEquals(TerminalColors.DEFAULT_BG, row[1].bg)
    }

    @Test
    fun `scroll margins isolate region and reverse index scrolls it down`() {
        val buffer = TerminalBuffer(rows = 4, cols = 4)
        buffer.write("111\r\n222\r\n333\r\n444")

        buffer.write("\u001B[2;3r\u001B[3;1H\n")
        assertEquals(listOf("111", "333", "", "444"), buffer.renderedLines())

        buffer.write("\u001B[2;1H\u001BM")
        assertEquals(listOf("111", "", "333", "444"), buffer.renderedLines())
    }

    @Test
    fun `cursor save restore and bracketed paste mode survive normal output`() {
        val buffer = TerminalBuffer(rows = 3, cols = 5)
        buffer.write("A\u001B7\u001B[2;2HX\u001B8B")
        assertEquals(listOf("AB", " X", ""), buffer.renderedLines())

        buffer.write("\u001B[?25;2004h")
        assertTrue(buffer.cursorVisible)
        assertTrue(buffer.snapshot().bracketedPasteMode)
        buffer.write("\u001B[?25;2004l")
        assertFalse(buffer.cursorVisible)
        assertFalse(buffer.snapshot().bracketedPasteMode)
    }

    @Test
    fun `overwriting a wide continuation clears the complete old glyph`() {
        val buffer = TerminalBuffer(rows = 2, cols = 6)
        buffer.write("\u4F60A\u001B[1;2HB")

        val row = buffer.visibleRows()[0]
        assertEquals(' ', row[0].char)
        assertEquals('B', row[1].char)
        assertFalse(row[1].wideContinuation)
        assertEquals('A', row[2].char)
    }

    @Test
    fun `scrollBy handles integer extremes without overflow`() {
        val buffer = TerminalBuffer(rows = 2, cols = 5, scrollbackLines = 3)
        repeat(6) { buffer.write("$it\r\n") }

        buffer.scrollBy(Int.MAX_VALUE)
        assertEquals(0, buffer.scrollTop)
        buffer.scrollBy(Int.MIN_VALUE)
        assertEquals(buffer.snapshot().screenTop, buffer.scrollTop)
    }

    @Test
    fun `clear cancels partial escape state and resets attributes`() {
        val buffer = TerminalBuffer(rows = 2, cols = 5)
        buffer.write("\u001B[31")
        buffer.clear()
        buffer.write("A")

        val cell = buffer.visibleRows()[0][0]
        assertEquals('A', cell.char)
        assertEquals(TerminalColors.DEFAULT_FG, cell.fg)
    }

    private fun TerminalBuffer.renderedLines(): List<String> = visibleRows().map { row ->
        buildString {
            row.forEach { cell ->
                if (!cell.wideContinuation) append(cell.char)
            }
        }.trimEnd()
    }
}
