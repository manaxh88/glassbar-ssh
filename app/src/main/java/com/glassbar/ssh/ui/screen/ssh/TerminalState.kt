package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalState {
    private val _textLines = MutableStateFlow<List<AnnotatedString>>(emptyList())
    val textLines: StateFlow<List<AnnotatedString>> = _textLines.asStateFlow()

    var isDarkTheme: Boolean = true

    private val rawBuffer = StringBuilder()
    private val lock = Any()

    fun write(text: String) {
        synchronized(lock) {
            for (ch in text) {
                if (ch == '\b') {
                    val idx = findLastVisibleCharIndex(rawBuffer)
                    if (idx != -1) {
                        rawBuffer.deleteCharAt(idx)
                    }
                } else {
                    rawBuffer.append(ch)
                }
            }
            processBufferLocked()
        }
    }

    private fun findLastVisibleCharIndex(sb: StringBuilder): Int {
        var i = sb.length - 1
        while (i >= 0) {
            val ch = sb[i]
            
            // 1. Skip CSI sequences: \u001B[ ... a-zA-Z
            if ((ch in 'a'..'z' || ch in 'A'..'Z') && i >= 2) {
                var j = i - 1
                var foundEscape = false
                while (j >= 0 && i - j < 20) {
                    if (sb[j] == '[' && j >= 1 && sb[j-1] == '\u001B') {
                        foundEscape = true
                        i = j - 2
                        break
                    }
                    if (sb[j] !in '0'..'9' && sb[j] != ';' && sb[j] != '?') {
                        break
                    }
                    j--
                }
                if (foundEscape) continue
            }
            
            // 2. Skip OSC sequences: \u001B] ... \u0007 or \u001B\
            if (ch == '\u0007' || ch == '\\') {
                var j = i - 1
                if (ch == '\\' && j >= 0 && sb[j] == '\u001B') {
                    j--
                }
                var foundEscape = false
                while (j >= 0 && i - j < 512) {
                    if (sb[j] == ']' && j >= 1 && sb[j-1] == '\u001B') {
                        foundEscape = true
                        i = j - 2
                        break
                    }
                    j--
                }
                if (foundEscape) continue
            }
            
            // 3. Skip 2-char escape sequences: \u001B followed by anything
            if (i >= 1 && sb[i-1] == '\u001B') {
                i -= 2
                continue
            }
            
            // This is a visible character (or a control character we don't care about skipping)
            return i
        }
        return -1
    }

    fun clear() {
        synchronized(lock) {
            rawBuffer.clear()
            _textLines.value = emptyList()
        }
    }

    fun getAllPlainText(): String {
        synchronized(lock) {
            return _textLines.value.joinToString("\n") { it.text }
        }
    }

    private fun processBufferLocked() {
        val raw = rawBuffer.toString()
        val lines = raw.split("\n")
        
        var currentColor: Color? = null
        var currentBgColor: Color? = null
        var isBold = false

        val processed = ArrayList<AnnotatedString>(lines.size)
        for (line in lines) {
            val result = parseAnsiLine(
                line = line,
                initialFg = currentColor,
                initialBg = currentBgColor,
                initialBold = isBold,
                isDark = isDarkTheme,
            )
            processed.add(result.annotated)
            currentColor = result.nextFg
            currentBgColor = result.nextBg
            isBold = result.nextBold
        }
        _textLines.value = processed
    }

    private data class ParseResult(
        val annotated: AnnotatedString,
        val nextFg: Color?,
        val nextBg: Color?,
        val nextBold: Boolean,
    )

    private fun parseAnsiLine(
        line: String,
        initialFg: Color?,
        initialBg: Color?,
        initialBold: Boolean,
        isDark: Boolean,
    ): ParseResult {
        var currentColor = initialFg
        var currentBgColor = initialBg
        var isBold = initialBold

        val annotated = buildAnnotatedString {
            var i = 0
            val len = line.length

            while (i < len) {
                val ch = line[i]

                if (ch == '\u001B') {
                    if (i + 1 < len) {
                        val nextChar = line[i + 1]

                        // Handle OSC (Operating System Command) sequences: \u001B] ... \u0007 or \u001B\
                        if (nextChar == ']') {
                            var end = i + 2
                            while (end < len) {
                                if (line[end] == '\u0007') {
                                    end++
                                    break
                                }
                                if (line[end] == '\u001B' && end + 1 < len && line[end + 1] == '\\') {
                                    end += 2
                                    break
                                }
                                end++
                            }
                            i = end
                            continue
                        }

                        // Handle CSI (Control Sequence Introducer) sequences: \u001B[ ...
                        if (nextChar == '[') {
                            var end = i + 2
                            while (end < len && line[end] !in 'a'..'z' && line[end] !in 'A'..'Z') {
                                end++
                            }
                            if (end < len) {
                                val seqCode = line.substring(i + 2, end)
                                val command = line[end]
                                if (command == 'm') {
                                    val params = if (seqCode.isEmpty()) listOf(0) else seqCode.split(";").mapNotNull { it.toIntOrNull() }
                                    var pIdx = 0
                                    while (pIdx < params.size) {
                                        val p = params[pIdx]
                                        when {
                                            p == 0 -> {
                                                currentColor = null
                                                currentBgColor = null
                                                isBold = false
                                            }
                                            p == 1 -> isBold = true
                                            p in 30..37 -> currentColor = getAnsi256Color(p - 30, isDark)
                                            p in 90..97 -> currentColor = getAnsi256Color(p - 90 + 8, isDark)
                                            p in 40..47 -> currentBgColor = getAnsi256Color(p - 40, isDark)
                                            p in 100..107 -> currentBgColor = getAnsi256Color(p - 100 + 8, isDark)
                                            p == 38 -> {
                                                if (pIdx + 1 < params.size && params[pIdx + 1] == 5 && pIdx + 2 < params.size) {
                                                    currentColor = getAnsi256Color(params[pIdx + 2], isDark)
                                                    pIdx += 2
                                                } else if (pIdx + 1 < params.size && params[pIdx + 1] == 2 && pIdx + 4 < params.size) {
                                                    currentColor = Color(params[pIdx + 2], params[pIdx + 3], params[pIdx + 4])
                                                    pIdx += 4
                                                }
                                            }
                                            p == 48 -> {
                                                if (pIdx + 1 < params.size && params[pIdx + 1] == 5 && pIdx + 2 < params.size) {
                                                    currentBgColor = getAnsi256Color(params[pIdx + 2], isDark)
                                                    pIdx += 2
                                                } else if (pIdx + 1 < params.size && params[pIdx + 1] == 2 && pIdx + 4 < params.size) {
                                                    currentBgColor = Color(params[pIdx + 2], params[pIdx + 3], params[pIdx + 4])
                                                    pIdx += 4
                                                }
                                            }
                                            p == 39 -> currentColor = null
                                            p == 49 -> currentBgColor = null
                                        }
                                        pIdx++
                                    }
                                }
                                i = end + 1
                                continue
                            }
                        }

                        // Other Escape sequences
                        i += 2
                        continue
                    } else {
                        i++
                        continue
                    }
                }

                // Ignore non-printable control characters (\u0007, \r, control codes < 0x20)
                if (ch == '\r' || (ch < ' ' && ch != '\t' && ch != '\n')) {
                    i++
                    continue
                }

                val effectiveColor = when {
                    currentColor != null -> currentColor
                    isBold -> if (isDark) Color(0xFF50FA7B) else Color(0xFF2E7D32) // Bright Green for bold text / prompts
                    else -> Color.Unspecified
                }

                val style = SpanStyle(
                    color = effectiveColor,
                    background = currentBgColor ?: Color.Unspecified,
                    fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                )
                withStyle(style) {
                    append(ch)
                }
                i++
            }
        }

        val resultAnnotated = applyPromptHighlighting(annotated, isDark)
        return ParseResult(resultAnnotated, currentColor, currentBgColor, isBold)
    }

    private val promptRegex = Regex("""([a-zA-Z0-9_-]+)@([a-zA-Z0-9_.-]+)""")

    private fun applyPromptHighlighting(annotated: AnnotatedString, isDark: Boolean): AnnotatedString {
        val text = annotated.text
        val matches = promptRegex.findAll(text)
        if (!matches.iterator().hasNext()) return annotated

        val builder = AnnotatedString.Builder(annotated)
        val userColor = if (isDark) Color(0xFF50FA7B) else Color(0xFF2E7D32)
        val atColor = if (isDark) Color(0xFF8BE9FD) else Color(0xFF00838F)
        val hostColor = if (isDark) Color(0xFFF1FA8C) else Color(0xFFE65100)

        for (match in matches) {
            val groupUser = match.groups[1]
            val groupHost = match.groups[2]

            if (groupUser != null) {
                builder.addStyle(
                    SpanStyle(color = userColor, fontWeight = FontWeight.Bold),
                    groupUser.range.first,
                    groupUser.range.last + 1,
                )
            }
            val atIndex = match.value.indexOf('@')
            if (atIndex != -1) {
                val absAt = match.range.first + atIndex
                builder.addStyle(
                    SpanStyle(color = atColor, fontWeight = FontWeight.Bold),
                    absAt,
                    absAt + 1,
                )
            }
            if (groupHost != null) {
                builder.addStyle(
                    SpanStyle(color = hostColor, fontWeight = FontWeight.Bold),
                    groupHost.range.first,
                    groupHost.range.last + 1,
                )
            }
        }
        return builder.toAnnotatedString()
    }

    private fun getAnsi256Color(index: Int, isDark: Boolean): Color {
        if (index in 0..15) {
            return if (isDark) {
                when (index) {
                    0 -> Color(0xFF4C566A) // Dark Grey
                    1 -> Color(0xFFFF5555) // Vibrant Red
                    2 -> Color(0xFF50FA7B) // Vibrant Neon Green (Termux / Dracula)
                    3 -> Color(0xFFF1FA8C) // Bright Yellow
                    4 -> Color(0xFF8BE9FD) // Cyan Blue
                    5 -> Color(0xFFFF79C6) // Pink Magenta
                    6 -> Color(0xFF8BE9FD) // Cyan
                    7 -> Color(0xFFF8F8F2) // White
                    8 -> Color(0xFF6272A4) // Medium Grey
                    9 -> Color(0xFFFF6E6E) // Bright Red
                    10 -> Color(0xFF69FF94) // Bright Neon Green
                    11 -> Color(0xFFFFFFA5) // Bright Yellow
                    12 -> Color(0xFFD6ACFF) // Purple
                    13 -> Color(0xFFFF92D0) // Bright Pink
                    14 -> Color(0xFF9AEDFE) // Bright Cyan
                    15 -> Color(0xFFFFFFFF) // Pure White
                    else -> Color.Unspecified
                }
            } else {
                when (index) {
                    0 -> Color(0xFF212121) // Black
                    1 -> Color(0xFFD32F2F) // Dark Red
                    2 -> Color(0xFF2E7D32) // Forest Green
                    3 -> Color(0xFFE65100) // Dark Amber
                    4 -> Color(0xFF1565C0) // Deep Blue
                    5 -> Color(0xFF7B1FA2) // Purple
                    6 -> Color(0xFF00838F) // Teal
                    7 -> Color(0xFF424242) // Dark Grey
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
            val n = index - 16
            val r = (n / 36) % 6
            val g = (n / 6) % 6
            val b = n % 6
            val red = if (r == 0) 0 else r * 40 + 55
            val green = if (g == 0) 0 else g * 40 + 55
            val blue = if (b == 0) 0 else b * 40 + 55
            return Color(red, green, blue)
        }
        if (index in 232..255) {
            val gray = (index - 232) * 10 + 8
            return Color(gray, gray, gray)
        }
        return Color.Unspecified
    }
}
