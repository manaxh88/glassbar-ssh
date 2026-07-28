package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

class TerminalEmulator(var columns: Int = 80, var rows: Int = 24) {
    var isDarkTheme: Boolean = true

    var grid = Array(rows) { Array(columns) { TerminalCell() } }
    var altGrid = Array(rows) { Array(columns) { TerminalCell() } }
    var isAltScreen = false

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == columns && newRows == rows) return
        if (newCols <= 0 || newRows <= 0) return

        val newGrid = Array(newRows) { Array(newCols) { TerminalCell() } }
        val newAltGrid = Array(newRows) { Array(newCols) { TerminalCell() } }

        val copyCols = minOf(columns, newCols)

        // Function to copy a grid with history push/pull
        fun resizeScreen(
            sourceGrid: Array<Array<TerminalCell>>,
            targetGrid: Array<Array<TerminalCell>>,
            isMainScreen: Boolean,
            currentCursorY: Int
        ): Int {
            var newCursorY = currentCursorY
            if (isMainScreen) {
                if (newRows < rows) {
                    var bottomLine = 0
                    for (y in rows - 1 downTo 0) {
                        var hasContent = false
                        for (x in 0 until columns) {
                            if (sourceGrid[y][x].char != ' ' || sourceGrid[y][x].bgColor != null) {
                                hasContent = true
                                break
                            }
                        }
                        if (hasContent) {
                            bottomLine = y
                            break
                        }
                    }
                    bottomLine = maxOf(bottomLine, currentCursorY)
                    
                    val rowsToPush = maxOf(0, bottomLine - newRows + 1)
                    
                    for (y in 0 until rowsToPush) {
                        val histRow = Array(columns) { TerminalCell() }
                        for (x in 0 until columns) histRow[x].copyFrom(sourceGrid[y][x])
                        history.add(histRow)
                    }
                    if (history.size > MAX_HISTORY) {
                        history.subList(0, history.size - MAX_HISTORY).clear()
                    }
                    for (y in 0 until newRows) {
                        if (y + rowsToPush < rows) {
                            for (x in 0 until copyCols) targetGrid[y][x].copyFrom(sourceGrid[y + rowsToPush][x])
                        }
                    }
                    newCursorY = maxOf(0, currentCursorY - rowsToPush)
                } else if (newRows > rows) {
                    val rowsToPull = minOf(newRows - rows, history.size)
                    for (y in 0 until rowsToPull) {
                        val histRow = history[history.size - rowsToPull + y]
                        val copyHistCols = minOf(histRow.size, newCols)
                        for (x in 0 until copyHistCols) targetGrid[y][x].copyFrom(histRow[x])
                    }
                    for (i in 0 until rowsToPull) history.removeAt(history.size - 1)
                    for (y in 0 until rows) {
                        for (x in 0 until copyCols) targetGrid[y + rowsToPull][x].copyFrom(sourceGrid[y][x])
                    }
                    newCursorY = currentCursorY + rowsToPull
                } else {
                    for (y in 0 until rows) {
                        for (x in 0 until copyCols) targetGrid[y][x].copyFrom(sourceGrid[y][x])
                    }
                }
            } else {
                for (y in 0 until minOf(rows, newRows)) {
                    for (x in 0 until copyCols) targetGrid[y][x].copyFrom(sourceGrid[y][x])
                }
                newCursorY = minOf(currentCursorY, newRows - 1)
            }
            return newCursorY
        }

        if (isAltScreen) {
            cursorY = resizeScreen(grid, newGrid, false, cursorY)
            savedCursorY = resizeScreen(altGrid, newAltGrid, true, savedCursorY)
        } else {
            cursorY = resizeScreen(grid, newGrid, true, cursorY)
            savedCursorY = resizeScreen(altGrid, newAltGrid, false, savedCursorY)
        }

        grid = newGrid
        altGrid = newAltGrid
        columns = newCols
        rows = newRows

        cursorX = minOf(cursorX, columns - 1)
        savedCursorX = minOf(savedCursorX, columns - 1)
        scrollRegionTop = 0
        scrollRegionBottom = rows - 1
    }

    var cursorX = 0
    var cursorY = 0
    
    var savedCursorX = 0
    var savedCursorY = 0

    var scrollRegionTop = 0
    var scrollRegionBottom = rows - 1

    var currentColor: Color? = null
    var currentBgColor: Color? = null
    var isBold = false

    val history = mutableListOf<Array<TerminalCell>>()
    val MAX_HISTORY = 1000

    private val inputBuffer = StringBuilder()

    fun clear() {
        history.clear()
        for (y in 0 until rows) {
            for (x in 0 until columns) grid[y][x].reset()
        }
        for (y in 0 until rows) {
            for (x in 0 until columns) altGrid[y][x].reset()
        }
        cursorX = 0
        cursorY = 0
        inputBuffer.clear()
    }

    fun process(text: String) {
        inputBuffer.append(text)
        var i = 0
        while (i < inputBuffer.length) {
            val ch = inputBuffer[i]
            
            if (ch == '\u001B') {
                if (i + 1 >= inputBuffer.length) break // Wait for more
                val nextChar = inputBuffer[i + 1]
                
                if (nextChar == ']') { // OSC
                    var end = i + 2
                    var foundEnd = false
                    while (end < inputBuffer.length) {
                        if (inputBuffer[end] == '\u0007') {
                            foundEnd = true
                            break
                        }
                        if (inputBuffer[end] == '\u001B' && end + 1 < inputBuffer.length && inputBuffer[end + 1] == '\\') {
                            end++
                            foundEnd = true
                            break
                        }
                        end++
                    }
                    if (!foundEnd) break // Incomplete
                    i = end + 1
                    continue
                }
                
                if (nextChar == '[') { // CSI
                    var end = i + 2
                    while (end < inputBuffer.length && inputBuffer[end].code in 0x20..0x3F) {
                        end++
                    }
                    if (end >= inputBuffer.length) break // Incomplete
                    if (inputBuffer[end].code in 0x40..0x7E) {
                        val seqCode = inputBuffer.substring(i + 2, end)
                        val command = inputBuffer[end]
                        handleCSI(command, seqCode)
                        i = end + 1
                        continue
                    }
                    // Invalid CSI, just skip ESC [
                    i += 2
                    continue
                }
                
                // 3-byte charsets
                if (nextChar in "()#%*+-$") {
                    if (i + 2 >= inputBuffer.length) break
                    i += 3
                    continue
                }
                
                // 2-byte escape sequences
                i += 2
                continue
            }
            
            // Handle control characters
            when (ch) {
                '\r' -> cursorX = 0
                '\n' -> {
                    cursorY++
                    if (cursorY > scrollRegionBottom) {
                        cursorY = scrollRegionBottom
                        scrollUp()
                    }
                }
                '\b' -> {
                    if (cursorX > 0) cursorX--
                }
                '\t' -> {
                    cursorX = (cursorX + 8) / 8 * 8
                    if (cursorX >= columns) cursorX = columns - 1
                }
                else -> {
                    if (ch >= ' ') {
                        // Printable character
                        if (cursorX >= columns) {
                            cursorX = 0
                            cursorY++
                            if (cursorY > scrollRegionBottom) {
                                cursorY = scrollRegionBottom
                                scrollUp()
                            }
                        }
                        val cell = grid[cursorY][cursorX]
                        cell.char = ch
                        cell.fgColor = currentColor
                        cell.bgColor = currentBgColor
                        cell.isBold = isBold
                        cursorX++
                    }
                }
            }
            i++
        }
        inputBuffer.delete(0, i)
    }

    private fun scrollUp() {
        if (!isAltScreen && scrollRegionTop == 0 && scrollRegionBottom == rows - 1) {
            // Save to history
            val topRow = Array(columns) { TerminalCell() }
            for (c in 0 until columns) topRow[c].copyFrom(grid[0][c])
            history.add(topRow)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }
        
        for (y in scrollRegionTop until scrollRegionBottom) {
            for (x in 0 until columns) {
                grid[y][x].copyFrom(grid[y + 1][x])
            }
        }
        for (x in 0 until columns) {
            grid[scrollRegionBottom][x].reset()
        }
    }

    private fun handleCSI(command: Char, seqCode: String) {
        val params = if (seqCode.isEmpty()) listOf(0) else seqCode.split(";").mapNotNull { it.toIntOrNull() }
        val p1 = params.getOrNull(0) ?: 0
        val p2 = params.getOrNull(1) ?: 0
        
        when (command) {
            'A' -> cursorY = maxOf(scrollRegionTop, cursorY - maxOf(1, p1))
            'B' -> cursorY = minOf(scrollRegionBottom, cursorY + maxOf(1, p1))
            'C' -> cursorX = minOf(columns - 1, cursorX + maxOf(1, p1))
            'D' -> cursorX = maxOf(0, cursorX - maxOf(1, p1))
            'd' -> { // VPA (Line Position Absolute)
                val row = maxOf(1, if (p1 == 0) 1 else p1)
                cursorY = minOf(rows - 1, row - 1)
            }
            'G', '`' -> { // CHA (Cursor Horizontal Absolute)
                val col = maxOf(1, if (p1 == 0) 1 else p1)
                cursorX = minOf(columns - 1, col - 1)
            }
            'H', 'f' -> {
                val row = maxOf(1, if (p1 == 0) 1 else p1)
                val col = maxOf(1, if (p2 == 0) 1 else p2)
                cursorY = minOf(rows - 1, row - 1)
                cursorX = minOf(columns - 1, col - 1)
            }
            'J' -> {
                when (p1) {
                    0 -> { // Clear below
                        for (x in cursorX until columns) grid[cursorY][x].reset()
                        for (y in cursorY + 1 until rows) {
                            for (x in 0 until columns) grid[y][x].reset()
                        }
                    }
                    1 -> { // Clear above
                        for (x in 0..minOf(cursorX, columns - 1)) grid[cursorY][x].reset()
                        for (y in 0 until cursorY) {
                            for (x in 0 until columns) grid[y][x].reset()
                        }
                    }
                    2, 3 -> { // Clear all
                        for (y in 0 until rows) {
                            for (x in 0 until columns) grid[y][x].reset()
                        }
                    }
                }
            }
            'K' -> {
                when (p1) {
                    0 -> for (x in minOf(cursorX, columns - 1) until columns) grid[cursorY][x].reset()
                    1 -> for (x in 0..minOf(cursorX, columns - 1)) grid[cursorY][x].reset()
                    2 -> for (x in 0 until columns) grid[cursorY][x].reset()
                }
            }
            'r' -> { // Set scrolling region
                scrollRegionTop = minOf(rows - 1, maxOf(0, (if (p1 == 0) 1 else p1) - 1))
                scrollRegionBottom = minOf(rows - 1, maxOf(0, (if (p2 == 0) rows else p2) - 1))
                cursorX = 0
                cursorY = 0
            }
            'h' -> {
                if (seqCode == "?1049" || seqCode == "?47") {
                    if (!isAltScreen) {
                        isAltScreen = true
                        for (y in 0 until rows) {
                            for (x in 0 until columns) {
                                altGrid[y][x].reset()
                            }
                        }
                        val temp = grid
                        grid = altGrid
                        altGrid = temp
                        
                        savedCursorX = cursorX
                        savedCursorY = cursorY
                        cursorX = 0
                        cursorY = 0
                    }
                }
            }
            'l' -> {
                if (seqCode == "?1049" || seqCode == "?47") {
                    if (isAltScreen) {
                        isAltScreen = false
                        val temp = grid
                        grid = altGrid
                        altGrid = temp
                        cursorX = savedCursorX
                        cursorY = savedCursorY
                    }
                }
            }
            'm' -> {
                var pIdx = 0
                val safeParams = if (params.isEmpty()) listOf(0) else params
                while (pIdx < safeParams.size) {
                    val p = safeParams[pIdx]
                    when {
                        p == 0 -> {
                            currentColor = null
                            currentBgColor = null
                            isBold = false
                        }
                        p == 1 -> isBold = true
                        p in 30..37 -> currentColor = getAnsi256Color(p - 30, isDarkTheme)
                        p in 90..97 -> currentColor = getAnsi256Color(p - 90 + 8, isDarkTheme)
                        p in 40..47 -> currentBgColor = getAnsi256Color(p - 40, isDarkTheme)
                        p in 100..107 -> currentBgColor = getAnsi256Color(p - 100 + 8, isDarkTheme)
                        p == 38 -> {
                            if (pIdx + 1 < safeParams.size && safeParams[pIdx + 1] == 5 && pIdx + 2 < safeParams.size) {
                                currentColor = getAnsi256Color(safeParams[pIdx + 2], isDarkTheme)
                                pIdx += 2
                            } else if (pIdx + 1 < safeParams.size && safeParams[pIdx + 1] == 2 && pIdx + 4 < safeParams.size) {
                                currentColor = Color(safeParams[pIdx + 2], safeParams[pIdx + 3], safeParams[pIdx + 4])
                                pIdx += 4
                            }
                        }
                        p == 48 -> {
                            if (pIdx + 1 < safeParams.size && safeParams[pIdx + 1] == 5 && pIdx + 2 < safeParams.size) {
                                currentBgColor = getAnsi256Color(safeParams[pIdx + 2], isDarkTheme)
                                pIdx += 2
                            } else if (pIdx + 1 < safeParams.size && safeParams[pIdx + 1] == 2 && pIdx + 4 < safeParams.size) {
                                currentBgColor = Color(safeParams[pIdx + 2], safeParams[pIdx + 3], safeParams[pIdx + 4])
                                pIdx += 4
                            }
                        }
                        p == 39 -> currentColor = null
                        p == 49 -> currentBgColor = null
                    }
                    pIdx++
                }
            }
        }
    }

    private fun getAnsi256Color(index: Int, isDark: Boolean): Color {
        if (index in 0..15) {
            return if (isDark) {
                when (index) {
                    0 -> Color(0xFF4C566A)
                    1 -> Color(0xFFFF5555)
                    2 -> Color(0xFF50FA7B)
                    3 -> Color(0xFFF1FA8C)
                    4 -> Color(0xFF8BE9FD)
                    5 -> Color(0xFFFF79C6)
                    6 -> Color(0xFF8BE9FD)
                    7 -> Color(0xFFF8F8F2)
                    8 -> Color(0xFF6272A4)
                    9 -> Color(0xFFFF6E6E)
                    10 -> Color(0xFF69FF94)
                    11 -> Color(0xFFFFFFA5)
                    12 -> Color(0xFFD6ACFF)
                    13 -> Color(0xFFFF92D0)
                    14 -> Color(0xFF9AEDFE)
                    15 -> Color(0xFFFFFFFF)
                    else -> Color.Unspecified
                }
            } else {
                when (index) {
                    0 -> Color(0xFF212121)
                    1 -> Color(0xFFD32F2F)
                    2 -> Color(0xFF2E7D32)
                    3 -> Color(0xFFE65100)
                    4 -> Color(0xFF1565C0)
                    5 -> Color(0xFF7B1FA2)
                    6 -> Color(0xFF00838F)
                    7 -> Color(0xFF424242)
                    8 -> Color(0xFF616161)
                    9 -> Color(0xFFC62828)
                    10 -> Color(0xFF1B5E20)
                    11 -> Color(0xFFBF360C)
                    12 -> Color(0xFF0D47A1)
                    13 -> Color(0xFF4A148C)
                    14 -> Color(0xFF006064)
                    15 -> Color(0xFF000000)
                    else -> Color.Unspecified
                }
            }
        }
        if (index in 16..231) {
            val res = index - 16
            val r = (res / 36) * 51
            val g = ((res / 6) % 6) * 51
            val b = (res % 6) * 51
            return Color(r, g, b)
        }
        if (index in 232..255) {
            val gray = (index - 232) * 10 + 8
            return Color(gray, gray, gray)
        }
        return Color.Unspecified
    }

    fun renderAsAnnotatedStrings(): List<AnnotatedString> {
        val list = mutableListOf<AnnotatedString>()
        if (!isAltScreen) {
            for (row in history) {
                list.add(renderRow(row))
            }
        }
        
        var lastVisibleRow = cursorY
        if (isAltScreen) {
            lastVisibleRow = rows - 1
        } else {
            for (y in rows - 1 downTo cursorY + 1) {
                var rowHasContent = false
                for (x in 0 until columns) {
                    val cell = grid[y][x]
                    if (cell.char != ' ' || cell.bgColor != null) {
                        rowHasContent = true
                        break
                    }
                }
                if (rowHasContent) {
                    lastVisibleRow = maxOf(lastVisibleRow, y)
                    break
                }
            }
        }
        
        for (y in 0..lastVisibleRow) {
            list.add(renderRow(grid[y]))
        }
        return list
    }

    private fun renderRow(row: Array<TerminalCell>): AnnotatedString {
        var lastCharIdx = row.size - 1
        while (lastCharIdx >= 0) {
            val cell = row[lastCharIdx]
            if (cell.char != ' ' || cell.bgColor != null) break
            lastCharIdx--
        }
        
        val builder = AnnotatedString.Builder()
        var currentColor: Color? = null
        var currentBgColor: Color? = null
        var currentBold = false
        var activeStylePos = 0
        var hasActiveStyle = false

        for (x in 0..lastCharIdx) {
            val cell = row[x]
            if (cell.fgColor != currentColor || cell.bgColor != currentBgColor || cell.isBold != currentBold) {
                if (hasActiveStyle) {
                    builder.addStyle(
                        SpanStyle(
                            color = currentColor ?: Color.Unspecified,
                            background = currentBgColor ?: Color.Unspecified,
                            fontWeight = if (currentBold) FontWeight.Bold else FontWeight.Normal
                        ),
                        activeStylePos,
                        builder.length
                    )
                }
                currentColor = cell.fgColor
                currentBgColor = cell.bgColor
                currentBold = cell.isBold
                activeStylePos = builder.length
                hasActiveStyle = true
            }
            builder.append(cell.char)
        }
        if (hasActiveStyle && builder.length > activeStylePos) {
            builder.addStyle(
                SpanStyle(
                    color = currentColor ?: Color.Unspecified,
                    background = currentBgColor ?: Color.Unspecified,
                    fontWeight = if (currentBold) FontWeight.Bold else FontWeight.Normal
                ),
                activeStylePos,
                builder.length
            )
        }
        return builder.toAnnotatedString()
    }
}
