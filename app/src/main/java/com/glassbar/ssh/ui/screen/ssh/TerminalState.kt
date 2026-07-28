package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalState {
    private val _textLines = MutableStateFlow<List<AnnotatedString>>(emptyList())
    val textLines: StateFlow<List<AnnotatedString>> = _textLines.asStateFlow()

    private val _cursorPosition = MutableStateFlow(Pair(0, 0))
    val cursorPosition: StateFlow<Pair<Int, Int>> = _cursorPosition.asStateFlow()

    private val _isAltScreen = MutableStateFlow(false)
    val isAltScreen: StateFlow<Boolean> = _isAltScreen.asStateFlow()

    private val emulator = TerminalEmulator(80, 24)
    private val lock = Any()

    val currentCols: Int get() = emulator.columns
    val currentRows: Int get() = emulator.rows

    var onPtyResize: ((cols: Int, rows: Int) -> Unit)? = null

    var isDarkTheme: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                synchronized(lock) {
                    emulator.isDarkTheme = value
                    _textLines.value = emulator.renderAsAnnotatedStrings().map { applyPromptHighlighting(it, value) }
                    _cursorPosition.value = Pair(emulator.cursorX, (if (emulator.isAltScreen) 0 else emulator.history.size) + emulator.cursorY)
                }
            }
        }

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) {
            if (cols == emulator.columns && rows == emulator.rows) return
            emulator.resize(cols, rows)
            _textLines.value = emulator.renderAsAnnotatedStrings().map { applyPromptHighlighting(it, isDarkTheme) }
            _cursorPosition.value = Pair(emulator.cursorX, (if (emulator.isAltScreen) 0 else emulator.history.size) + emulator.cursorY)
            onPtyResize?.invoke(cols, rows)
        }
    }

    fun write(text: String) {
        synchronized(lock) {
            emulator.process(text)
            _textLines.value = emulator.renderAsAnnotatedStrings().map { applyPromptHighlighting(it, isDarkTheme) }
            _cursorPosition.value = Pair(emulator.cursorX, (if (emulator.isAltScreen) 0 else emulator.history.size) + emulator.cursorY)
            if (_isAltScreen.value != emulator.isAltScreen) _isAltScreen.value = emulator.isAltScreen
        }
    }

    fun clear() {
        synchronized(lock) {
            emulator.clear()
            _textLines.value = emptyList()
            _cursorPosition.value = Pair(0, 0)
            if (_isAltScreen.value != emulator.isAltScreen) _isAltScreen.value = emulator.isAltScreen
        }
    }

    fun getAllPlainText(): String {
        synchronized(lock) {
            return _textLines.value.joinToString("\n") { it.text }
        }
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
}
