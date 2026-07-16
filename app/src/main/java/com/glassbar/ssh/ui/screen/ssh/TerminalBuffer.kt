package com.glassbar.ssh.ui.screen.ssh

import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/** ANSI/xterm color palette. */
object TerminalColors {
    private val basePalette = intArrayOf(
        0xFF000000.toInt(), 0xFFCC0000.toInt(), 0xFF4E9A06.toInt(), 0xFFC4A000.toInt(),
        0xFF3465A4.toInt(), 0xFF75507B.toInt(), 0xFF06989A.toInt(), 0xFFD3D7CF.toInt(),
        0xFF555753.toInt(), 0xFFEF2929.toInt(), 0xFF8AE234.toInt(), 0xFFFCE94F.toInt(),
        0xFF729FCF.toInt(), 0xFFAD7FA8.toInt(), 0xFF34E2E2.toInt(), 0xFFEEEEEC.toInt(),
    )

    fun fg(index: Int): Int = resolve(index)
    fun bg(index: Int): Int = resolve(index)

    /** Encodes a direct RGB color without colliding with palette indexes or terminal defaults. */
    fun rgb(red: Int, green: Int, blue: Int): Int = TRUE_COLOR_FLAG or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)

    private fun resolve(index: Int): Int = when {
        index >= TRUE_COLOR_FLAG -> 0xFF000000.toInt() or (index and 0x00FFFFFF)
        else -> resolvePalette(index.coerceIn(0, 255))
    }

    private fun resolvePalette(color: Int): Int = when (color) {
        in 0..15 -> basePalette[color]
        in 16..231 -> {
            val value = color - 16
            val r = xtermComponent(value / 36)
            val g = xtermComponent((value / 6) % 6)
            val b = xtermComponent(value % 6)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
        else -> {
            val gray = 8 + (color - 232) * 10
            0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray
        }
    }

    private fun xtermComponent(value: Int): Int = if (value == 0) 0 else 55 + value * 40

    // Defaults remain distinct from explicit ANSI foreground/background colors.
    const val DEFAULT_FG = -1
    const val DEFAULT_BG = -1
    private const val TRUE_COLOR_FLAG = 0x01000000
}

/** A single immutable terminal cell. */
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = TerminalColors.DEFAULT_FG,
    val bg: Int = TerminalColors.DEFAULT_BG,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    /** True for the second grid cell occupied by a wide character. */
    val wideContinuation: Boolean = false,
)

data class TerminalPosition(val row: Int, val col: Int)

data class TerminalSelection(
    val start: TerminalPosition,
    val end: TerminalPosition,
) {
    fun normalized(): TerminalSelection = if (
        start.row < end.row || (start.row == end.row && start.col <= end.col)
    ) {
        this
    } else {
        TerminalSelection(end, start)
    }
}

/** Read-only row used by [TerminalSnapshot]. */
class TerminalSnapshotRow internal constructor(
    private val cells: Array<TerminalCell>,
) {
    val size: Int get() = cells.size
    operator fun get(index: Int): TerminalCell = cells[index]
    internal fun copyCells(): Array<TerminalCell> = cells.copyOf()
}

/**
 * Immutable, thread-safe rendering state. Output parsing may update the buffer on a worker
 * thread while the Android view keeps drawing the last complete snapshot on the main thread.
 */
class TerminalSnapshot internal constructor(
    val rows: Int,
    val cols: Int,
    val viewportTop: Int,
    val screenTop: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val bracketedPasteMode: Boolean,
    val revision: Long,
    val lines: List<TerminalSnapshotRow>,
)

/**
 * ANSI terminal grid backed by a line-level circular scrollback buffer.
 *
 * All mutations are serialized by [lock]. Filling the scrollback rotates a row reference in
 * O(cols) (to clear the recycled row), rather than moving every retained row.
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

    private val lock = Any()

    @Volatile
    var rows: Int = rows
        private set

    @Volatile
    var cols: Int = cols
        private set

    private var currentFg = TerminalColors.DEFAULT_FG
    private var currentBg = TerminalColors.DEFAULT_BG
    private var currentBold = false
    private var currentUnderline = false
    private var currentInverse = false
    private var bracketedPasteMode = false

    private var capacity = rows + scrollbackLines
    private var ringHead = 0
    private var ringRows = Array(capacity) { newBlankRow(cols) }

    /** Compatibility view of all logical rows. Prefer [snapshot] for rendering. */
    val cells: Array<Array<TerminalCell>>
        get() = synchronized(lock) {
            Array(capacity) { logicalRow(it).copyOf() }
        }

    /** Logical first row of the active terminal screen. */
    private var screenTop = 0
    private var scrollMarginTop = 0
    private var scrollMarginBottom = rows - 1
    private var savedCursorRelativeRow = 0
    private var savedCursorCol = 0

    @Volatile
    var cursorRow: Int = 0
        private set

    @Volatile
    var cursorCol: Int = 0
        private set

    @Volatile
    var cursorVisible: Boolean = true
        private set

    /** Logical top row displayed by the viewport. */
    @Volatile
    var scrollTop: Int = 0
        private set

    private var escapeState = EscapeState.NORMAL
    private val csiParams = ArrayList<Int>(8)
    private var csiPrivate = false
    private var csiLastWasSeparator = false
    private val currentParam = StringBuilder(8)
    private val oscString = StringBuilder(64)
    private var oscOverflow = false
    private val titleString = StringBuilder(64)

    private val changeListeners = CopyOnWriteArrayList<TerminalBuffer.() -> Unit>()
    private var revision = 0L

    @Volatile
    private var renderSnapshot: TerminalSnapshot

    init {
        synchronized(lock) {
            renderSnapshot = buildSnapshotLocked()
        }
    }

    fun addChangeListener(listener: TerminalBuffer.() -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    fun removeChangeListener(listener: TerminalBuffer.() -> Unit) {
        changeListeners.remove(listener)
    }

    /** Returns the latest complete rendering state without locking the parser thread. */
    fun snapshot(): TerminalSnapshot = renderSnapshot

    /** Resize the grid while retaining as much history and active-screen content as possible. */
    fun resize(newRows: Int, newCols: Int) {
        val targetRows = newRows.coerceAtLeast(1)
        val targetCols = newCols.coerceAtLeast(1)
        var changed = false
        synchronized(lock) {
            if (targetRows == rows && targetCols == cols) return

            val oldRows = rows
            val oldCols = cols
            val oldScreenTop = screenTop
            val oldScrollTop = scrollTop
            val oldCursorRow = cursorRow
            val oldCursorRelative = (oldCursorRow - oldScreenTop).coerceIn(0, oldRows - 1)
            val viewportWasAtBottom = oldScrollTop == oldScreenTop

            // When shrinking, choose the active-screen slice that keeps the cursor visible.
            val screenSliceStart = if (targetRows < oldRows) {
                (oldCursorRelative - targetRows + 1).coerceIn(0, oldRows - targetRows)
            } else {
                0
            }
            val preservedEnd = if (targetRows < oldRows) {
                oldScreenTop + screenSliceStart + targetRows
            } else {
                oldScreenTop + oldRows
            }
            val targetCapacity = targetRows + scrollbackLines
            val preservedStart = (preservedEnd - targetCapacity).coerceAtLeast(0)
            val preserved = ArrayList<Array<TerminalCell>>(targetCapacity)

            for (logicalIndex in preservedStart until preservedEnd) {
                val oldLine = logicalRow(logicalIndex)
                val resizedLine = newBlankRow(targetCols)
                System.arraycopy(oldLine, 0, resizedLine, 0, min(oldCols, targetCols))
                sanitizeWideCells(resizedLine, targetCols)
                preserved.add(resizedLine)
            }

            // Growing the active screen keeps the old screen at the top and adds blank rows below.
            if (targetRows > oldRows) {
                repeat(targetRows - oldRows) { preserved.add(newBlankRow(targetCols)) }
            }
            while (preserved.size < targetRows) preserved.add(newBlankRow(targetCols))
            while (preserved.size > targetCapacity) preserved.removeAt(0)

            rows = targetRows
            cols = targetCols
            capacity = targetCapacity
            ringHead = 0
            ringRows = Array(capacity) { index ->
                if (index < preserved.size) preserved[index] else newBlankRow(cols)
            }
            screenTop = (preserved.size - rows).coerceIn(0, scrollbackLines)
            scrollMarginTop = 0
            scrollMarginBottom = rows - 1

            val droppedRows = preservedStart
            cursorRow = (oldCursorRow - droppedRows).coerceIn(screenTop, screenTop + rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            savedCursorRelativeRow = savedCursorRelativeRow.coerceIn(0, rows - 1)
            savedCursorCol = savedCursorCol.coerceIn(0, cols - 1)
            scrollTop = if (viewportWasAtBottom) {
                screenTop
            } else {
                (oldScrollTop - droppedRows).coerceIn(0, screenTop)
            }
            publishSnapshotLocked()
            changed = true
        }
        if (changed) notifyChange()
    }

    /** Feed a byte-oriented stream into the ANSI parser. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "Invalid byte range: offset=$offset length=$length size=${data.size}"
        }
        if (length == 0) return
        synchronized(lock) {
            for (i in offset until offset + length) processByte(data[i].toInt() and 0xFF)
            publishSnapshotLocked()
        }
        notifyChange()
    }

    fun write(data: ByteArray) = write(data, 0, data.size)

    /** Feed decoded UTF-16 text; ASCII controls still pass through the ANSI state machine. */
    fun write(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            for (ch in text) {
                if (ch.code > 127) putChar(ch) else processByte(ch.code)
            }
            publishSnapshotLocked()
        }
        notifyChange()
    }

    /** Safe compatibility API. Returned rows are detached from the mutable ring. */
    fun visibleRows(): List<Array<TerminalCell>> = snapshot().lines.map { it.copyCells() }

    fun getTitle(): String = synchronized(lock) { titleString.toString() }

    /** Extract selected text, omitting wide-character continuation cells and trailing spaces. */
    fun textInRange(selection: TerminalSelection): String = synchronized(lock) {
        val normalized = selection.normalized()
        val lastLogicalRow = screenTop + rows - 1
        val firstRow = normalized.start.row.coerceIn(0, lastLogicalRow)
        val lastRow = normalized.end.row.coerceIn(firstRow, lastLogicalRow)
        buildString {
            for (rowIndex in firstRow..lastRow) {
                val line = logicalRow(rowIndex)
                val firstCol = if (rowIndex == firstRow) normalized.start.col.coerceIn(0, cols - 1) else 0
                val lastCol = if (rowIndex == lastRow) normalized.end.col.coerceIn(0, cols - 1) else cols - 1
                var trimmedEnd = lastCol
                while (trimmedEnd >= firstCol && (line[trimmedEnd].char == ' ' || line[trimmedEnd].wideContinuation)) {
                    trimmedEnd--
                }
                for (col in firstCol..trimmedEnd) {
                    if (!line[col].wideContinuation) append(line[col].char)
                }
                if (rowIndex != lastRow) append('\n')
            }
        }
    }

    private fun processByte(value: Int) {
        when (escapeState) {
            EscapeState.NORMAL -> processNormal(value)
            EscapeState.ESCAPE -> processEscape(value)
            EscapeState.CSI -> processCsi(value)
            EscapeState.CSI_IGNORE -> processCsiIgnore(value)
            EscapeState.OSC -> processOsc(value)
            EscapeState.OSC_ESCAPE -> processOscEscape(value)
        }
    }

    private fun processNormal(value: Int) {
        when (value) {
            0x1B -> escapeState = EscapeState.ESCAPE
            0x07 -> Unit
            0x08 -> if (cursorCol > 0) cursorCol--
            0x09 -> cursorCol = min(((cursorCol / 8) + 1) * 8, cols - 1)
            0x0A -> lineFeed()
            0x0D -> cursorCol = 0
            in 0x20..0x7E, in 0xA0..0xFF -> putChar(value.toChar())
        }
    }

    private fun processEscape(value: Int) {
        when (value) {
            '['.code -> beginCsi()
            ']'.code -> {
                escapeState = EscapeState.OSC
                oscString.clear()
                oscOverflow = false
            }
            '7'.code -> {
                saveCursor()
                escapeState = EscapeState.NORMAL
            }
            '8'.code -> {
                restoreCursor()
                escapeState = EscapeState.NORMAL
            }
            'D'.code -> {
                lineFeed()
                escapeState = EscapeState.NORMAL
            }
            'M'.code -> {
                reverseLineFeed()
                escapeState = EscapeState.NORMAL
            }
            'c'.code -> {
                fullReset(clearScreen = true)
                escapeState = EscapeState.NORMAL
            }
            0x1B -> escapeState = EscapeState.ESCAPE
            else -> escapeState = EscapeState.NORMAL
        }
    }

    private fun beginCsi() {
        escapeState = EscapeState.CSI
        csiParams.clear()
        currentParam.clear()
        csiPrivate = false
        csiLastWasSeparator = false
    }

    private fun processCsi(value: Int) {
        when {
            value == 0x1B -> escapeState = EscapeState.ESCAPE
            value == 0x18 || value == 0x1A -> escapeState = EscapeState.NORMAL
            value in '0'.code..'9'.code -> {
                csiLastWasSeparator = false
                if (currentParam.length < MAX_CSI_PARAM_DIGITS) {
                    currentParam.append(value.toChar())
                } else {
                    escapeState = EscapeState.CSI_IGNORE
                }
            }
            value == ';'.code -> {
                if (csiParams.size >= MAX_CSI_PARAMS) {
                    escapeState = EscapeState.CSI_IGNORE
                } else {
                    csiParams.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam.clear()
                    csiLastWasSeparator = true
                }
            }
            value == '?'.code && csiParams.isEmpty() && currentParam.isEmpty() -> csiPrivate = true
            value in 0x20..0x2F -> Unit // Intermediate bytes are consumed by the final byte.
            value in 0x40..0x7E -> {
                if (currentParam.isNotEmpty()) {
                    csiParams.add(currentParam.toString().toIntOrNull() ?: 0)
                    currentParam.clear()
                } else if (csiLastWasSeparator && csiParams.size < MAX_CSI_PARAMS) {
                    csiParams.add(0)
                }
                val p0 = csiParams.getOrElse(0) { 0 }
                val p1 = csiParams.getOrElse(1) { 0 }
                when (value.toChar()) {
                    'A' -> cursorUp(max(p0, 1))
                    'B' -> cursorDown(max(p0, 1))
                    'C' -> cursorForward(max(p0, 1))
                    'D' -> cursorBack(max(p0, 1))
                    'E' -> {
                        cursorDown(max(p0, 1)); cursorCol = 0
                    }
                    'F' -> {
                        cursorUp(max(p0, 1)); cursorCol = 0
                    }
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
                    'h' -> if (csiPrivate) applyPrivateModes(enabled = true)
                    'l' -> if (csiPrivate) applyPrivateModes(enabled = false)
                    'r' -> if (!csiPrivate) setScrollMargins(p0, p1)
                    's' -> saveCursor()
                    'u' -> restoreCursor()
                }
                escapeState = EscapeState.NORMAL
            }
            else -> escapeState = EscapeState.NORMAL
        }
    }

    private fun processCsiIgnore(value: Int) {
        when {
            value == 0x1B -> escapeState = EscapeState.ESCAPE
            value in 0x40..0x7E || value == 0x18 || value == 0x1A -> escapeState = EscapeState.NORMAL
        }
    }

    private fun processOsc(value: Int) {
        when (value) {
            0x07 -> finishOsc()
            0x1B -> escapeState = EscapeState.OSC_ESCAPE
            0x18, 0x1A -> escapeState = EscapeState.NORMAL
            else -> appendOsc(value)
        }
    }

    private fun processOscEscape(value: Int) {
        if (value == '\\'.code) {
            finishOsc()
        } else {
            // ESC not followed by ST is data in the OSC payload.
            appendOsc(0x1B)
            appendOsc(value)
            escapeState = EscapeState.OSC
        }
    }

    private fun appendOsc(value: Int) {
        if (oscString.length < MAX_OSC_LENGTH) {
            oscString.append(value.toChar())
        } else {
            oscOverflow = true
        }
    }

    private fun finishOsc() {
        if (!oscOverflow) {
            val title = oscString.toString()
            if (title.startsWith("0;") || title.startsWith("2;")) {
                titleString.clear()
                titleString.append(title.substring(2))
            }
        }
        oscString.clear()
        oscOverflow = false
        escapeState = EscapeState.NORMAL
    }

    private fun putChar(char: Char) {
        val charWidth = if (isWideCharacter(char)) 2 else 1
        if (cursorCol >= cols || cursorCol + charWidth > cols) {
            cursorCol = 0
            lineFeed()
        }
        val line = logicalRow(cursorRow)
        if (cursorCol in 0 until cols) {
            clearCellOccupant(line, cursorCol)
            if (charWidth == 2) clearCellOccupant(line, cursorCol + 1)
            line[cursorCol] = TerminalCell(
                char = char,
                fg = currentFg,
                bg = currentBg,
                bold = currentBold,
                underline = currentUnderline,
                inverse = currentInverse,
            )
            if (charWidth == 2 && cursorCol + 1 < cols) {
                line[cursorCol + 1] = TerminalCell(
                    fg = currentFg,
                    bg = currentBg,
                    bold = currentBold,
                    underline = currentUnderline,
                    inverse = currentInverse,
                    wideContinuation = true,
                )
            }
        }
        cursorCol += charWidth
    }

    private fun isWideCharacter(char: Char): Boolean = when (char.code) {
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
        val marginBottom = screenTop + scrollMarginBottom
        when {
            cursorRow == marginBottom && scrollMarginTop == 0 && scrollMarginBottom == rows - 1 -> {
                appendHistoryLine()
            }
            cursorRow == marginBottom -> {
                scrollRegionUp(screenTop + scrollMarginTop, marginBottom, 1)
            }
            cursorRow < screenTop + rows - 1 -> cursorRow++
        }
    }

    private fun reverseLineFeed() {
        val marginTop = screenTop + scrollMarginTop
        if (cursorRow == marginTop) {
            scrollRegionDown(marginTop, screenTop + scrollMarginBottom, 1)
        } else if (cursorRow > screenTop) {
            cursorRow--
        }
    }

    private fun appendHistoryLine() {
        val viewportWasAtBottom = scrollTop == screenTop
        if (screenTop < scrollbackLines) {
            screenTop++
            cursorRow++
        } else {
            // Drop the oldest logical row by rotating the ring; no retained row is copied.
            ringHead = (ringHead + 1) % capacity
            cursorRow = capacity - 1
            if (!viewportWasAtBottom && scrollTop > 0) scrollTop--
        }
        clearRow(screenTop + rows - 1)
        if (viewportWasAtBottom) scrollTop = screenTop
    }

    private fun scrollUp(count: Int = 1) {
        scrollRegionUp(
            screenTop + scrollMarginTop,
            screenTop + scrollMarginBottom,
            count,
        )
    }

    private fun scrollRegionUp(top: Int, bottom: Int, count: Int) {
        val regionRows = bottom - top + 1
        val amount = count.coerceIn(1, regionRows)
        val recycled = Array(amount) { logicalRow(top + it) }
        for (row in top..bottom - amount) setLogicalRow(row, logicalRow(row + amount))
        for (offset in 0 until amount) {
            clearRowArray(recycled[offset])
            setLogicalRow(bottom - amount + 1 + offset, recycled[offset])
        }
    }

    private fun scrollDown(count: Int = 1) {
        scrollRegionDown(
            screenTop + scrollMarginTop,
            screenTop + scrollMarginBottom,
            count,
        )
    }

    private fun scrollRegionDown(top: Int, bottom: Int, count: Int) {
        val regionRows = bottom - top + 1
        val amount = count.coerceIn(1, regionRows)
        val recycled = Array(amount) { logicalRow(bottom - amount + 1 + it) }
        for (row in bottom downTo top + amount) setLogicalRow(row, logicalRow(row - amount))
        for (offset in 0 until amount) {
            clearRowArray(recycled[offset])
            setLogicalRow(top + offset, recycled[offset])
        }
    }

    private fun eraseDisplay(param: Int) {
        when (param) {
            0 -> {
                eraseLine(0)
                for (row in cursorRow + 1..screenTop + rows - 1) clearRow(row)
            }
            1 -> {
                for (row in screenTop until cursorRow) clearRow(row)
                eraseLine(1)
            }
            else -> {
                for (row in screenTop..screenTop + rows - 1) clearRow(row)
            }
        }
    }

    private fun eraseLine(param: Int) {
        val line = logicalRow(cursorRow)
        val first: Int
        val last: Int
        when (param) {
            0 -> {
                first = cursorCol.coerceAtMost(cols); last = cols - 1
            }
            1 -> {
                first = 0; last = cursorCol.coerceIn(0, cols - 1)
            }
            else -> {
                first = 0; last = cols - 1
            }
        }
        if (first > last) return
        val blank = blankCell()
        for (col in first..last) clearCellOccupant(line, col, blank)
    }

    private fun insertLines(count: Int) {
        val top = screenTop + scrollMarginTop
        val bottom = screenTop + scrollMarginBottom
        if (cursorRow !in top..bottom) return
        val amount = count.coerceIn(1, bottom - cursorRow + 1)
        val recycled = Array(amount) { logicalRow(bottom - amount + 1 + it) }
        for (row in bottom downTo cursorRow + amount) setLogicalRow(row, logicalRow(row - amount))
        for (offset in 0 until amount) {
            clearRowArray(recycled[offset])
            setLogicalRow(cursorRow + offset, recycled[offset])
        }
    }

    private fun deleteLines(count: Int) {
        val top = screenTop + scrollMarginTop
        val bottom = screenTop + scrollMarginBottom
        if (cursorRow !in top..bottom) return
        val amount = count.coerceIn(1, bottom - cursorRow + 1)
        val recycled = Array(amount) { logicalRow(cursorRow + it) }
        for (row in cursorRow..bottom - amount) setLogicalRow(row, logicalRow(row + amount))
        for (offset in 0 until amount) {
            clearRowArray(recycled[offset])
            setLogicalRow(bottom - amount + 1 + offset, recycled[offset])
        }
    }

    private fun deleteChars(count: Int) {
        if (cursorCol !in 0 until cols) return
        val amount = count.coerceIn(1, cols - cursorCol)
        val line = logicalRow(cursorRow)
        for (col in cursorCol until cols - amount) line[col] = line[col + amount]
        val blank = blankCell()
        for (col in cols - amount until cols) line[col] = blank
        sanitizeWideCells(line, cols)
    }

    private fun insertChars(count: Int) {
        if (cursorCol !in 0 until cols) return
        val amount = count.coerceIn(1, cols - cursorCol)
        val line = logicalRow(cursorRow)
        for (col in cols - 1 downTo cursorCol + amount) line[col] = line[col - amount]
        val blank = blankCell()
        for (col in cursorCol until cursorCol + amount) line[col] = blank
        sanitizeWideCells(line, cols)
    }

    private fun cursorUp(count: Int) {
        cursorRow = max(cursorRow - count, screenTop)
    }

    private fun cursorDown(count: Int) {
        cursorRow = min(cursorRow + count, screenTop + rows - 1)
    }

    private fun cursorForward(count: Int) {
        cursorCol = min(cursorCol + count, cols - 1)
    }

    private fun cursorBack(count: Int) {
        cursorCol = max(cursorCol - count, 0)
    }

    private fun saveCursor() {
        savedCursorRelativeRow = (cursorRow - screenTop).coerceIn(0, rows - 1)
        savedCursorCol = cursorCol.coerceIn(0, cols - 1)
    }

    private fun restoreCursor() {
        cursorRow = screenTop + savedCursorRelativeRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
    }

    private fun setScrollMargins(topParam: Int, bottomParam: Int) {
        val top = (if (topParam <= 0) 0 else topParam - 1).coerceIn(0, rows - 1)
        val bottom = (if (bottomParam <= 0) rows - 1 else bottomParam - 1).coerceIn(0, rows - 1)
        if (top >= bottom && rows > 1) return
        scrollMarginTop = top
        scrollMarginBottom = bottom
        cursorRow = screenTop + top
        cursorCol = 0
    }

    private fun applyPrivateModes(enabled: Boolean) {
        for (mode in csiParams) {
            when (mode) {
                25 -> cursorVisible = enabled
                2004 -> bracketedPasteMode = enabled
            }
        }
    }

    private fun applySgr() {
        if (csiParams.isEmpty()) csiParams.add(0)
        var index = 0
        while (index < csiParams.size) {
            when (val param = csiParams[index]) {
                0 -> {
                    currentFg = TerminalColors.DEFAULT_FG
                    currentBg = TerminalColors.DEFAULT_BG
                    currentBold = false
                    currentUnderline = false
                    currentInverse = false
                }
                1 -> currentBold = true
                4 -> currentUnderline = true
                7 -> currentInverse = true
                22 -> currentBold = false
                24 -> currentUnderline = false
                27 -> currentInverse = false
                39 -> currentFg = TerminalColors.DEFAULT_FG
                49 -> currentBg = TerminalColors.DEFAULT_BG
                in 30..37 -> currentFg = param - 30
                in 40..47 -> currentBg = param - 40
                in 90..97 -> currentFg = param - 90 + 8
                in 100..107 -> currentBg = param - 100 + 8
                38 -> when {
                    index + 2 < csiParams.size && csiParams[index + 1] == 5 -> {
                        currentFg = csiParams[index + 2].coerceIn(0, 255)
                        index += 2
                    }
                    index + 4 < csiParams.size && csiParams[index + 1] == 2 -> {
                        currentFg = TerminalColors.rgb(
                            csiParams[index + 2],
                            csiParams[index + 3],
                            csiParams[index + 4],
                        )
                        index += 4
                    }
                }
                48 -> when {
                    index + 2 < csiParams.size && csiParams[index + 1] == 5 -> {
                        currentBg = csiParams[index + 2].coerceIn(0, 255)
                        index += 2
                    }
                    index + 4 < csiParams.size && csiParams[index + 1] == 2 -> {
                        currentBg = TerminalColors.rgb(
                            csiParams[index + 2],
                            csiParams[index + 3],
                            csiParams[index + 4],
                        )
                        index += 4
                    }
                }
            }
            index++
        }
    }

    private fun fullReset(clearScreen: Boolean = false) {
        currentFg = TerminalColors.DEFAULT_FG
        currentBg = TerminalColors.DEFAULT_BG
        currentBold = false
        currentUnderline = false
        currentInverse = false
        cursorVisible = true
        bracketedPasteMode = false
        scrollMarginTop = 0
        scrollMarginBottom = rows - 1
        savedCursorRelativeRow = 0
        savedCursorCol = 0
        titleString.clear()
        csiParams.clear()
        currentParam.clear()
        oscString.clear()
        csiPrivate = false
        csiLastWasSeparator = false
        oscOverflow = false
        if (clearScreen) clearStorageLocked()
    }

    fun clear() {
        synchronized(lock) {
            fullReset(clearScreen = true)
            escapeState = EscapeState.NORMAL
            publishSnapshotLocked()
        }
        notifyChange()
    }

    fun reset() {
        synchronized(lock) {
            fullReset(clearScreen = true)
            escapeState = EscapeState.NORMAL
            publishSnapshotLocked()
        }
        notifyChange()
    }

    /** Positive values move toward older content; negative values move toward the live screen. */
    fun scrollBy(delta: Int) {
        var changed = false
        synchronized(lock) {
            val next = (scrollTop.toLong() - delta.toLong())
                .coerceIn(0L, screenTop.toLong())
                .toInt()
            if (next != scrollTop) {
                scrollTop = next
                publishSnapshotLocked()
                changed = true
            }
        }
        if (changed) notifyChange()
    }

    fun scrollToBottom() {
        var changed = false
        synchronized(lock) {
            if (scrollTop != screenTop) {
                scrollTop = screenTop
                publishSnapshotLocked()
                changed = true
            }
        }
        if (changed) notifyChange()
    }

    private fun logicalRow(logicalIndex: Int): Array<TerminalCell> {
        require(logicalIndex in 0 until capacity) { "row $logicalIndex outside 0 until $capacity" }
        return ringRows[(ringHead + logicalIndex) % capacity]
    }

    private fun setLogicalRow(logicalIndex: Int, row: Array<TerminalCell>) {
        ringRows[(ringHead + logicalIndex) % capacity] = row
    }

    private fun clearRow(logicalIndex: Int) = clearRowArray(logicalRow(logicalIndex))

    private fun clearRowArray(row: Array<TerminalCell>) {
        Arrays.fill(row, blankCell())
    }

    private fun clearStorageLocked() {
        val blank = blankCell()
        for (line in ringRows) Arrays.fill(line, blank)
        ringHead = 0
        screenTop = 0
        scrollTop = 0
        cursorRow = 0
        cursorCol = 0
    }

    private fun clearCellOccupant(
        row: Array<TerminalCell>,
        column: Int,
        blank: TerminalCell = blankCell(),
    ) {
        if (column !in 0 until min(cols, row.size)) return
        val cell = row[column]
        if (cell.wideContinuation && column > 0) {
            row[column - 1] = blank
        } else if (
            isWideCharacter(cell.char) &&
            column + 1 < min(cols, row.size) &&
            row[column + 1].wideContinuation
        ) {
            row[column + 1] = blank
        }
        row[column] = blank
    }

    private fun sanitizeWideCells(row: Array<TerminalCell>, width: Int) {
        val limit = min(width, row.size)
        val blank = blankCell()
        for (column in 0 until limit) {
            val cell = row[column]
            when {
                cell.wideContinuation && (
                    column == 0 ||
                        !isWideCharacter(row[column - 1].char) ||
                        row[column - 1].wideContinuation
                    ) -> row[column] = blank
                isWideCharacter(cell.char) && (
                    column + 1 >= limit || !row[column + 1].wideContinuation
                    ) -> row[column] = blank
            }
        }
    }

    private fun blankCell() = TerminalCell(bg = currentBg)

    private fun newBlankRow(width: Int): Array<TerminalCell> {
        val blank = TerminalCell(bg = currentBg)
        return Array(width) { blank }
    }

    private fun publishSnapshotLocked() {
        revision++
        renderSnapshot = buildSnapshotLocked()
    }

    private fun buildSnapshotLocked(): TerminalSnapshot {
        val lines = ArrayList<TerminalSnapshotRow>(rows)
        for (row in scrollTop until scrollTop + rows) {
            lines.add(TerminalSnapshotRow(logicalRow(row).copyOf()))
        }
        return TerminalSnapshot(
            rows = rows,
            cols = cols,
            viewportTop = scrollTop,
            screenTop = screenTop,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            bracketedPasteMode = bracketedPasteMode,
            revision = revision,
            lines = lines,
        )
    }

    private fun notifyChange() {
        for (listener in changeListeners) listener(this)
    }

    private enum class EscapeState { NORMAL, ESCAPE, CSI, CSI_IGNORE, OSC, OSC_ESCAPE }

    private companion object {
        const val MAX_CSI_PARAMS = 32
        const val MAX_CSI_PARAM_DIGITS = 9
        const val MAX_OSC_LENGTH = 4096
    }
}
