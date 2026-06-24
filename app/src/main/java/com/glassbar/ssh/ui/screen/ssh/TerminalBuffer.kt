package com.glassbar.ssh.ui.screen.ssh

import kotlin.math.min
import kotlin.math.max

/**
 * ANSI color palette - standard 16 colors.
 */
object TerminalColors {
    private val palette = intArrayOf(
        0xFF000000.toInt(), // 0 Black
        0xFFCC0000.toInt(), // 1 Red
        0xFF4E9A06.toInt(), // 2 Green
        0xFFC4A000.toInt(), // 3 Yellow
        0xFF3465A4.toInt(), // 4 Blue
        0xFF75507B.toInt(), // 5 Magenta
        0xFF06989A.toInt(), // 6 Cyan
        0xFFD3D7CF.toInt(), // 7 White
        0xFF555753.toInt(), // 8 Bright Black
        0xFFEF2929.toInt(), // 9 Bright Red
        0xFF8AE234.toInt(), // 10 Bright Green
        0xFFFCE94F.toInt(), // 11 Bright Yellow
        0xFF729FCF.toInt(), // 12 Bright Blue
        0xFFAD7FA8.toInt(), // 13 Bright Magenta
        0xFF34E2E2.toInt(), // 14 Bright Cyan
        0xFFEEEEEC.toInt(), // 15 Bright White
    )

    fun fg(index: Int): Int = palette[index.coerceIn(0, 15)]
    fun bg(index: Int): Int = palette[index.coerceIn(0, 15)]

    const val DEFAULT_FG = 7
    const val DEFAULT_BG = 0
}

/**
 * A single character cell in the terminal.
 */
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = TerminalColors.DEFAULT_FG,
    val bg: Int = TerminalColors.DEFAULT_BG,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

/**
 * Terminal buffer that maintains a character grid and parses ANSI escape sequences.
 */
class TerminalBuffer(
    val rows: Int = 24,
    val cols: Int = 80,
    val scrollbackLines: Int = 1000,
) {
    private val totalRows = rows + scrollbackLines
    val cells: Array<Array<TerminalCell>> = Array(totalRows) { Array(cols) { TerminalCell() } }

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var cursorVisible: Boolean = true
        private set

    /** Top of the visible area in the scrollback buffer */
    var scrollTop: Int = scrollbackLines
        private set

    private var currentFg: Int = TerminalColors.DEFAULT_FG
    private var currentBg: Int = TerminalColors.DEFAULT_BG
    private var currentBold: Boolean = false
    private var currentUnderline: Boolean = false
    private var currentInverse: Boolean = false

    // ANSI parsing state
    private var escapeState: EscapeState = EscapeState.NORMAL
    private var csiParams = mutableListOf<Int>()
    private var csiPrivate = false
    private var currentParam = StringBuilder()
    private var oscString = StringBuilder()
    private var titleString = StringBuilder()

    private val changeListeners = mutableListOf<TerminalBuffer.() -> Unit>()

    fun addChangeListener(listener: TerminalBuffer.() -> Unit) {
        changeListeners.add(listener)
    }

    private fun notifyChange() {
        for (listener in changeListeners) {
            listener(this)
        }
    }

    /**
     * Feed raw bytes from SSH into the terminal parser.
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (i in offset until offset + length) {
            processByte(data[i].toInt() and 0xFF)
        }
        notifyChange()
    }

    fun write(data: ByteArray) = write(data, 0, data.size)

    /**
     * Write a String, handling multi-byte UTF-8 characters directly.
     * Non-ASCII characters (code > 127) skip ANSI parsing and are placed on screen directly.
     */
    fun write(text: String) {
        for (ch in text) {
            if (ch.code > 127) {
                // Wide character (e.g. Chinese) — place directly without ANSI parsing
                putChar(ch)
            } else {
                // ASCII or control character — run through ANSI state machine
                processByte(ch.code)
            }
        }
        notifyChange()
    }

    /**
     * Get the visible region (the rows currently displayed on screen).
     */
    fun visibleRows(): List<Array<TerminalCell>> {
        return (scrollTop until scrollTop + rows).map { cells[it] }
    }

    fun getTitle(): String = titleString.toString()

    private fun processByte(b: Int) {
        when (escapeState) {
            EscapeState.NORMAL -> processNormal(b)
            EscapeState.ESCAPE -> processEscape(b)
            EscapeState.CSI -> processCsi(b)
            EscapeState.OSC -> processOsc(b)
        }
    }

    private fun processNormal(b: Int) {
        when (b) {
            0x1B -> escapeState = EscapeState.ESCAPE
            0x07 -> {} // BEL - ignore
            0x08 -> { // BS - backspace
                if (cursorCol > 0) cursorCol--
            }
            0x09 -> { // TAB
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = min(nextTab, cols - 1)
            }
            0x0A -> { // LF
                lineFeed()
            }
            0x0D -> { // CR
                cursorCol = 0
            }
            in 0x20..0x7E, in 0xA0..0xFF -> {
                putChar(b.toChar())
            }
        }
    }

    private fun processEscape(b: Int) {
        when (b) {
            '['.code -> beginCsi()
            ']'.code -> {
                escapeState = EscapeState.OSC
                oscString.clear()
            }
            '7'.code, '8'.code -> escapeState = EscapeState.NORMAL
            'D'.code -> { lineFeed(); escapeState = EscapeState.NORMAL }
            'M'.code -> { reverseLineFeed(); escapeState = EscapeState.NORMAL }
            'c'.code -> { fullReset(); escapeState = EscapeState.NORMAL }
            else -> escapeState = EscapeState.NORMAL
        }
    }

    private fun beginCsi() {
        escapeState = EscapeState.CSI
        csiParams.clear()
        currentParam.clear()
        csiPrivate = false
    }

    private fun processCsi(b: Int) {
        when {
            b in '0'.code..'9'.code -> currentParam.append(b.toChar())
            b == ';'.code -> {
                csiParams.add(currentParam.toString().toIntOrNull() ?: 0)
                currentParam.clear()
            }
            b == '?'.code -> csiPrivate = true
            else -> {
                if (currentParam.isNotEmpty()) {
                    csiParams.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam.clear()
                }
                while (csiParams.size < 4) csiParams.add(0)

                val p0 = csiParams.getOrElse(0) { 0 }
                val p1 = csiParams.getOrElse(1) { 0 }

                when (b.toChar()) {
                    'A' -> cursorUp(max(p0, 1))
                    'B' -> cursorDown(max(p0, 1))
                    'C' -> cursorForward(max(p0, 1))
                    'D' -> cursorBack(max(p0, 1))
                    'E' -> { cursorDown(max(p0, 1)); cursorCol = 0 }
                    'F' -> { cursorUp(max(p0, 1)); cursorCol = 0 }
                    'G' -> cursorCol = (max(p0, 1) - 1).coerceIn(0, cols - 1)
                    'H', 'f' -> {
                        val row = if (p0 > 0) p0 - 1 else 0
                        val col = if (p1 > 0) p1 - 1 else 0
                        cursorRow = (scrollTop + row).coerceIn(0, totalRows - 1)
                        cursorCol = col.coerceIn(0, cols - 1)
                    }
                    'J' -> eraseDisplay(p0)
                    'K' -> eraseLine(p0)
                    'L' -> insertLines(max(p0, 1))
                    'M' -> deleteLines(max(p0, 1))
                    'P' -> deleteChars(max(p0, 1))
                    '@' -> insertChars(max(p0, 1))
                    'S' -> scrollUp(max(p0, 1))
                    'T' -> scrollDown(max(p0, 1))
                    'm' -> applySgr()
                    'h' -> { if (csiPrivate && p0 == 25) cursorVisible = true }
                    'l' -> { if (csiPrivate && p0 == 25) cursorVisible = false }
                    'r' -> {} // scrolling region - ignore
                }
                escapeState = EscapeState.NORMAL
            }
        }
    }

    private fun processOsc(b: Int) {
        if (b == 0x07 || (b == 0x1B && oscString.isNotEmpty())) {
            val str = oscString.toString()
            if (str.startsWith("0;") || str.startsWith("2;")) {
                titleString.clear()
                titleString.append(str.substring(2))
            }
            escapeState = if (b == 0x1B) EscapeState.ESCAPE else EscapeState.NORMAL
        } else if (b != 0x1B) {
            oscString.append(b.toChar())
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        val row = cursorRow
        if (row in 0 until totalRows && cursorCol in 0 until cols) {
            cells[row][cursorCol] = TerminalCell(
                char = ch, fg = currentFg, bg = currentBg,
                bold = currentBold, underline = currentUnderline, inverse = currentInverse,
            )
        }
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow < scrollTop + rows - 1) {
            cursorRow++
        } else {
            scrollUp()
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow > scrollTop) cursorRow--
    }

    private fun scrollUp(count: Int = 1) {
        val bottom = scrollTop + rows - 1
        for (i in scrollTop until bottom - count + 1) {
            for (j in 0 until cols) cells[i][j] = cells[i + count][j]
        }
        for (i in max(bottom - count + 1, scrollTop)..bottom) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun scrollDown(count: Int = 1) {
        val bottom = scrollTop + rows - 1
        for (i in bottom downTo scrollTop + count) {
            for (j in 0 until cols) cells[i][j] = cells[i - count][j]
        }
        for (i in scrollTop until scrollTop + count) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun eraseDisplay(param: Int) {
        val startRow: Int; val endRow: Int
        when (param) {
            0 -> { eraseLine(0); startRow = cursorRow + 1; endRow = scrollTop + rows - 1 }
            1 -> { startRow = scrollTop; endRow = cursorRow - 1; eraseLine(1) }
            else -> {
                startRow = scrollTop; endRow = scrollTop + rows - 1
                cursorRow = scrollTop; cursorCol = 0
            }
        }
        for (r in startRow..endRow) {
            for (c in 0 until cols) cells[r][c] = TerminalCell(bg = currentBg)
        }
    }

    private fun eraseLine(param: Int) {
        val startCol: Int; val endCol: Int
        when (param) {
            0 -> { startCol = cursorCol; endCol = cols - 1 }
            1 -> { startCol = 0; endCol = cursorCol }
            else -> { startCol = 0; endCol = cols - 1 }
        }
        for (c in startCol..endCol) {
            if (cursorRow in 0 until totalRows) cells[cursorRow][c] = TerminalCell(bg = currentBg)
        }
    }

    private fun insertLines(count: Int) {
        val bottom = scrollTop + rows - 1
        for (i in bottom downTo cursorRow + count) {
            for (j in 0 until cols) cells[i][j] = cells[i - count][j]
        }
        for (i in cursorRow until cursorRow + count) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun deleteLines(count: Int) {
        val bottom = scrollTop + rows - 1
        for (i in cursorRow until bottom - count + 1) {
            for (j in 0 until cols) cells[i][j] = cells[i + count][j]
        }
        for (i in max(bottom - count + 1, cursorRow)..bottom) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun deleteChars(count: Int) {
        for (c in cursorCol until cols - count) cells[cursorRow][c] = cells[cursorRow][c + count]
        for (c in max(cols - count, 0) until cols) cells[cursorRow][c] = TerminalCell(bg = currentBg)
    }

    private fun insertChars(count: Int) {
        for (c in cols - 1 downTo cursorCol + count) cells[cursorRow][c] = cells[cursorRow][c - count]
        for (c in cursorCol until min(cursorCol + count, cols)) cells[cursorRow][c] = TerminalCell(bg = currentBg)
    }

    private fun cursorUp(n: Int) { cursorRow = max(cursorRow - n, scrollTop) }
    private fun cursorDown(n: Int) { cursorRow = min(cursorRow + n, scrollTop + rows - 1) }
    private fun cursorForward(n: Int) { cursorCol = min(cursorCol + n, cols - 1) }
    private fun cursorBack(n: Int) { cursorCol = max(cursorCol - n, 0) }

    private fun applySgr() {
        var i = 0
        while (i < csiParams.size) {
            when (val p = csiParams[i]) {
                0 -> { currentFg = TerminalColors.DEFAULT_FG; currentBg = TerminalColors.DEFAULT_BG; currentBold = false; currentUnderline = false; currentInverse = false }
                1 -> currentBold = true
                4 -> currentUnderline = true
                7 -> currentInverse = true
                27 -> currentInverse = false
                in 30..37 -> currentFg = p - 30
                in 40..47 -> currentBg = p - 40
                in 90..97 -> currentFg = p - 90 + 8
                in 100..107 -> currentBg = p - 100 + 8
                38 -> { if (i + 2 < csiParams.size && csiParams[i + 1] == 5) { currentFg = csiParams[i + 2].coerceIn(0, 15); i += 2 } }
                48 -> { if (i + 2 < csiParams.size && csiParams[i + 1] == 5) { currentBg = csiParams[i + 2].coerceIn(0, 15); i += 2 } }
                else -> {}
            }
            i++
        }
    }

    private fun fullReset() {
        currentFg = TerminalColors.DEFAULT_FG; currentBg = TerminalColors.DEFAULT_BG
        currentBold = false; currentUnderline = false; currentInverse = false
        cursorVisible = true
    }

    fun clear() {
        for (r in 0 until totalRows) {
            for (c in 0 until cols) cells[r][c] = TerminalCell(bg = currentBg)
        }
        cursorRow = scrollTop; cursorCol = 0
        notifyChange()
    }

    fun reset() {
        fullReset(); clear()
    }

    private enum class EscapeState { NORMAL, ESCAPE, CSI, OSC }
}
