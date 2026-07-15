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

    // Keep terminal defaults distinct from explicit ANSI white/black so the
    // renderer can honor `37m` and `40m` on its light default surface.
    const val DEFAULT_FG = -1
    const val DEFAULT_BG = -1
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
    rows: Int = 24,
    cols: Int = 80,
    val scrollbackLines: Int = 1000,
) {
    init {
        require(rows > 0) { "rows must be positive" }
        require(cols > 0) { "cols must be positive" }
        require(scrollbackLines >= 0) { "scrollbackLines cannot be negative" }
    }

    var rows: Int = rows
        private set
    var cols: Int = cols
        private set
    private var totalRows = rows + scrollbackLines
    var cells: Array<Array<TerminalCell>> = Array(totalRows) { Array(cols) { TerminalCell() } }
        private set

    /** Absolute first row of the active terminal screen. */
    private var screenTop: Int = 0

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var cursorVisible: Boolean = true
        private set

    /** Top of the visible area in the scrollback buffer */
    var scrollTop: Int = 0
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

    fun removeChangeListener(listener: TerminalBuffer.() -> Unit) {
        changeListeners.remove(listener)
    }

    /** Resize the logical terminal grid to match the native view and its PTY. */
    fun resize(newRows: Int, newCols: Int) {
        val targetRows = newRows.coerceAtLeast(1)
        val targetCols = newCols.coerceAtLeast(1)
        if (targetRows == rows && targetCols == cols) return

        val oldRows = rows
        val oldCols = cols
        val oldScreenTop = screenTop
        val oldCursorRow = cursorRow
        val oldCursorRelative = (oldCursorRow - oldScreenTop).coerceIn(0, oldRows - 1)
        val oldVisible = (oldScreenTop until oldScreenTop + oldRows)
            .map { cells[it].copyOf() }

        rows = targetRows
        cols = targetCols
        totalRows = rows + scrollbackLines
        cells = Array(totalRows) { Array(cols) { TerminalCell(bg = currentBg) } }
        screenTop = 0
        scrollTop = 0
        cursorCol = (cursorCol.coerceIn(0, oldCols - 1)).coerceIn(0, cols - 1)

        val copyRows = minOf(oldRows, rows)
        val copyCols = minOf(oldCols, cols)
        val sourceRowStart = if (rows < oldRows) {
            (oldCursorRelative - rows + 1).coerceIn(0, oldRows - rows)
        } else {
            0
        }
        cursorRow = (oldCursorRelative - sourceRowStart).coerceIn(0, rows - 1)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                cells[r][c] = oldVisible[sourceRowStart + r][c]
            }
        }
        notifyChange()
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
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid byte range: offset=$offset length=$length size=${data.size}"
        }
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
                        cursorRow = (screenTop + row).coerceIn(screenTop, screenTop + rows - 1)
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
        val charWidth = if (isWideCharacter(ch)) 2 else 1
        if (cursorCol >= cols || cursorCol + charWidth > cols) {
            cursorCol = 0
            lineFeed()
        }
        val row = cursorRow
        if (row in 0 until totalRows && cursorCol in 0 until cols) {
            val cell = TerminalCell(
                char = ch, fg = currentFg, bg = currentBg,
                bold = currentBold, underline = currentUnderline, inverse = currentInverse,
            )
            cells[row][cursorCol] = cell
            if (charWidth == 2 && cursorCol + 1 < cols) {
                // Reserve the trailing cell so following ASCII keeps its PTY column.
                cells[row][cursorCol + 1] = cell.copy(char = ' ')
            }
        }
        cursorCol += charWidth
    }

    private fun isWideCharacter(ch: Char): Boolean = when (ch.code) {
        in 0x1100..0x115F,
        in 0x2329..0x232A,
        in 0x2E80..0x303E,
        in 0x3040..0xA4CF,
        in 0xAC00..0xD7A3,
        in 0xF900..0xFAFF,
        in 0xFE10..0xFE19,
        in 0xFE30..0xFE6F,
        in 0xFF00..0xFF60,
        in 0xFFE0..0xFFE6 -> true
        else -> false
    }

    private fun lineFeed() {
        if (cursorRow < screenTop + rows - 1) {
            cursorRow++
        } else {
            appendHistoryLine()
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow > screenTop) cursorRow--
    }

    /** Advance the active screen by one row while retaining scrollback. */
    private fun appendHistoryLine() {
        val viewportWasAtBottom = scrollTop == screenTop
        if (screenTop < scrollbackLines) {
            screenTop++
            cursorRow++
        } else {
            for (r in 0 until totalRows - 1) {
                cells[r] = cells[r + 1]
            }
            cells[totalRows - 1] = Array(cols) { TerminalCell(bg = currentBg) }
            cursorRow = totalRows - 1
            if (!viewportWasAtBottom && scrollTop > 0) scrollTop--
        }
        for (c in 0 until cols) {
            cells[screenTop + rows - 1][c] = TerminalCell(bg = currentBg)
        }
        if (viewportWasAtBottom) scrollTop = screenTop
    }

    private fun scrollUp(count: Int = 1) {
        val amount = count.coerceIn(1, rows)
        val bottom = screenTop + rows - 1
        for (i in screenTop until bottom - amount + 1) {
            for (j in 0 until cols) cells[i][j] = cells[i + amount][j]
        }
        for (i in max(bottom - amount + 1, screenTop)..bottom) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun scrollDown(count: Int = 1) {
        val amount = count.coerceIn(1, rows)
        val bottom = screenTop + rows - 1
        for (i in bottom downTo screenTop + amount) {
            for (j in 0 until cols) cells[i][j] = cells[i - amount][j]
        }
        for (i in screenTop until screenTop + amount) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun eraseDisplay(param: Int) {
        val startRow: Int; val endRow: Int
        when (param) {
            0 -> { eraseLine(0); startRow = cursorRow + 1; endRow = screenTop + rows - 1 }
            1 -> { startRow = screenTop; endRow = cursorRow - 1; eraseLine(1) }
            else -> {
                startRow = screenTop; endRow = screenTop + rows - 1
                cursorRow = screenTop; cursorCol = 0
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
        if (cursorRow !in screenTop..(screenTop + rows - 1)) return
        val amount = count.coerceIn(1, screenTop + rows - cursorRow)
        val bottom = screenTop + rows - 1
        for (i in bottom downTo cursorRow + amount) {
            for (j in 0 until cols) cells[i][j] = cells[i - amount][j]
        }
        for (i in cursorRow until cursorRow + amount) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in screenTop..(screenTop + rows - 1)) return
        val amount = count.coerceIn(1, screenTop + rows - cursorRow)
        val bottom = screenTop + rows - 1
        for (i in cursorRow until bottom - amount + 1) {
            for (j in 0 until cols) cells[i][j] = cells[i + amount][j]
        }
        for (i in max(bottom - amount + 1, cursorRow)..bottom) {
            for (j in 0 until cols) cells[i][j] = TerminalCell(bg = currentBg)
        }
    }

    private fun deleteChars(count: Int) {
        if (cursorRow !in 0 until totalRows) return
        for (c in cursorCol until cols - count) cells[cursorRow][c] = cells[cursorRow][c + count]
        for (c in max(cols - count, 0) until cols) cells[cursorRow][c] = TerminalCell(bg = currentBg)
    }

    private fun insertChars(count: Int) {
        if (cursorRow !in 0 until totalRows) return
        for (c in cols - 1 downTo cursorCol + count) cells[cursorRow][c] = cells[cursorRow][c - count]
        for (c in cursorCol until min(cursorCol + count, cols)) cells[cursorRow][c] = TerminalCell(bg = currentBg)
    }

    private fun cursorUp(n: Int) { cursorRow = max(cursorRow - n, screenTop) }
    private fun cursorDown(n: Int) { cursorRow = min(cursorRow + n, screenTop + rows - 1) }
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
        screenTop = 0
        scrollTop = 0
        cursorRow = 0; cursorCol = 0
        notifyChange()
    }

    fun reset() {
        fullReset(); clear()
    }

    /**
     * Scroll the viewport by delta lines. Positive = scroll up (see older content).
     */
    fun scrollBy(delta: Int) {
        scrollTop = (scrollTop - delta).coerceIn(0, screenTop)
        notifyChange()
    }

    fun scrollToBottom() {
        if (scrollTop == screenTop) return
        scrollTop = screenTop
        notifyChange()
    }

    private enum class EscapeState { NORMAL, ESCAPE, CSI, OSC }
}
