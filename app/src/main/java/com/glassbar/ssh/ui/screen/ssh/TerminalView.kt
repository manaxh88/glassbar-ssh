package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private val TermFg = android.graphics.Color.rgb(26, 26, 26)
private val TermBg = android.graphics.Color.rgb(250, 250, 250)

@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    onKeyEvent: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val context = LocalContext.current
    var showVirtualKeyboard by remember { mutableStateOf(false) }

    val terminalView = remember(context, buffer) {
        TerminalNativeView(context, buffer)
    }
    // The callback can change while the native view is retained by AndroidView.
    // Keep the view from sending input to a stale session after reconnecting.
    LaunchedEffect(onKeyEvent, terminalView, buffer) {
        terminalView.keyListener = { key ->
            buffer.scrollToBottom()
            onKeyEvent(key)
        }
    }
    LaunchedEffect(terminalView) {
        terminalView.onFocusChanged = { showVirtualKeyboard = it }
    }

    LaunchedEffect(onResize, terminalView) {
        terminalView.onTerminalSizeChanged = onResize
    }

    DisposableEffect(buffer, terminalView) {
        val listener: TerminalBuffer.() -> Unit = {
            terminalView.postInvalidate()
        }
        buffer.addChangeListener(listener)
        onDispose { buffer.removeChangeListener(listener) }
    }

    LaunchedEffect(terminalView) {
        while (true) {
            delay(250)
            terminalView.invalidate()
        }
    }

    // Terminal gets focus on tap — no auto-focus to avoid showing keyboard initially

    Column(modifier = modifier) {
        AndroidView(
            factory = { terminalView },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(3.dp)
                .focusRequester(focusRequester),
        )
        AnimatedVisibility(
            visible = showVirtualKeyboard,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            VirtualKeyboard(
                onKey = { key ->
                    buffer.scrollToBottom()
                    onKeyEvent(key)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private class TerminalNativeView(
    context: Context,
    private val buffer: TerminalBuffer,
) : View(context) {

    var keyListener: (String) -> Unit = {}
    var onFocusChanged: (Boolean) -> Unit = {}
    var onTerminalSizeChanged: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var lastDeleteSurroundingTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, hasFocus ->
            handler.post { onFocusChanged(hasFocus) }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            if (hasFocus) imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            else imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private var scrollFraction = 0f

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        val charWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val lineHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent)
            .coerceAtLeast(1f)
        val cols = (width / charWidth).toInt().coerceIn(8, 240)
        val rows = (height / lineHeight).toInt().coerceIn(4, 120)
        buffer.resize(rows, cols)
        onTerminalSizeChanged(cols, rows)
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Invert dy: finger down → show content above
            scrollFraction -= dy * 0.4f
            val lines = scrollFraction.toInt()
            if (lines != 0) {
                buffer.scrollBy(lines)
                scrollFraction -= lines.toFloat()
            }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (hasFocus()) {
                clearFocus()
            } else {
                requestFocus()
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    // IMEs commonly commit Enter as a newline instead of a key event.
                    val str = it.toString().replace('\n', '\r')
                    if (str.isNotEmpty()) keyListener(str)
                }
                return super.commitText(text, newCursorPosition)
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // TYPE_NULL prevents composing; if reached, don't forward to avoid duplicate chars
                return super.setComposingText(text, newCursorPosition)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                lastDeleteSurroundingTime = System.currentTimeMillis()
                repeat(beforeLength.coerceAtMost(200)) { keyListener("\u007F") }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                // DEL fallback: use if deleteSurroundingText not called recently (< 80ms)
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (System.currentTimeMillis() - lastDeleteSurroundingTime > 80) {
                        keyListener("\u007F")
                    }
                    return true
                }
                // Enter
                if (event.keyCode == KeyEvent.KEYCODE_ENTER) { keyListener("\r"); return true }
                // Ctrl combinations often still report a printable unicodeChar
                // (Ctrl+C reports 'c'), so let the mapper handle modifiers first.
                if (event.isCtrlPressed || event.unicodeChar == 0) {
                    val str = keyEventToString(event)
                    if (str != null) keyListener(str)
                }
                return true
            }
            override fun performEditorAction(actionCode: Int): Boolean {
                if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                ) {
                    keyListener("\r")
                    return true
                }
                return super.performEditorAction(actionCode)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val str = keyEventToString(event)
        if (str != null) {
            keyListener(str)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        textSize = 32f
        color = TermFg
    }
    private val bgPaint = Paint()
    private val cursorPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val canvasWidth = width.toFloat()
        val canvasHeight = height.toFloat()
        if (canvasWidth <= 0 || canvasHeight <= 0) return

        // The PTY was sized to buffer.cols.  Wrapping a logical row again at a
        // different number of visual columns makes prompts and full-screen TUIs
        // drift out of sync, so always draw exactly the negotiated grid width.
        val drawCols = buffer.cols.coerceAtLeast(1)
        val drawRows = buffer.rows.coerceAtLeast(1)
        val cellW = canvasWidth / drawCols
        val cellH = canvasHeight / drawRows
        val targetTextSize = minOf(cellW * 1.6f, cellH * 0.85f, 40f).coerceAtLeast(1f)
        textPaint.textSize = targetTextSize
        val measuredCharW = textPaint.measureText("M")
        if (measuredCharW > cellW && measuredCharW > 0f) {
            textPaint.textSize *= cellW / measuredCharW
        }
        val fontMetrics = textPaint.fontMetrics
        canvas.drawColor(TermBg)
        val visibleRows = buffer.visibleRows()
        val maxVisualRows = drawRows

        for (r in visibleRows.indices) {
            val rowTop = r * cellH
            if (rowTop >= canvasHeight) break
            val row = visibleRows[r]
            for (c in 0 until minOf(row.size, drawCols)) {
                val cell = row[c]
                val x = c * cellW
                val background = if (cell.inverse) {
                    if (cell.fg == TerminalColors.DEFAULT_FG) TermFg else TerminalColors.fg(cell.fg)
                } else if (cell.bg != TerminalColors.DEFAULT_BG) {
                    TerminalColors.bg(cell.bg)
                } else {
                    null
                }
                if (background != null) {
                    bgPaint.color = background
                    canvas.drawRect(x, rowTop, x + cellW, rowTop + cellH, bgPaint)
                }

                if (cell.char == ' ') continue

                textPaint.color = if (cell.inverse) {
                    if (cell.bg == TerminalColors.DEFAULT_BG) TermBg else TerminalColors.bg(cell.bg)
                } else if (cell.fg == TerminalColors.DEFAULT_FG) {
                    TermFg
                } else {
                    TerminalColors.fg(cell.fg)
                }
                textPaint.isFakeBoldText = cell.bold
                textPaint.isUnderlineText = cell.underline

                val baseline = rowTop + (cellH - fontMetrics.descent - fontMetrics.ascent) / 2f
                canvas.drawText(cell.char.toString(), x, baseline, textPaint)
            }
        }

        val curCol = buffer.cursorCol
        val curRow = buffer.cursorRow - buffer.scrollTop
        val times = System.currentTimeMillis() % 1060
        if (times < 530 && buffer.cursorVisible) {
            val cursorVisRow = curRow + (curCol / drawCols)
            val cursorVisCol = (curCol % drawCols).coerceIn(0, drawCols - 1)
            if (cursorVisRow in 0 until maxVisualRows && cursorVisRow * cellH < canvasHeight) {
                cursorPaint.color = android.graphics.Color.argb(100, 0, 0, 0)
                canvas.drawRect(cursorVisCol * cellW, cursorVisRow * cellH,
                    (cursorVisCol + 1) * cellW, (cursorVisRow + 1) * cellH, cursorPaint)
            }
        }
    }
}

private fun keyEventToString(event: KeyEvent): String? {
    // Check Ctrl before unicodeChar: Android still reports the printable
    // character for Ctrl+C/D/Z on many hardware keyboards.
    if (event.isCtrlPressed) {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_A -> "\u0001"
            KeyEvent.KEYCODE_B -> "\u0002"
            KeyEvent.KEYCODE_C -> "\u0003"
            KeyEvent.KEYCODE_D -> "\u0004"
            KeyEvent.KEYCODE_E -> "\u0005"
            KeyEvent.KEYCODE_F -> "\u0006"
            KeyEvent.KEYCODE_G -> "\u0007"
            KeyEvent.KEYCODE_H -> "\u0008"
            KeyEvent.KEYCODE_I -> "\u0009"
            KeyEvent.KEYCODE_J -> "\u000A"
            KeyEvent.KEYCODE_K -> "\u000B"
            KeyEvent.KEYCODE_L -> "\u000C"
            KeyEvent.KEYCODE_M -> "\u000D"
            KeyEvent.KEYCODE_N -> "\u000E"
            KeyEvent.KEYCODE_O -> "\u000F"
            KeyEvent.KEYCODE_P -> "\u0010"
            KeyEvent.KEYCODE_Q -> "\u0011"
            KeyEvent.KEYCODE_R -> "\u0012"
            KeyEvent.KEYCODE_S -> "\u0013"
            KeyEvent.KEYCODE_T -> "\u0014"
            KeyEvent.KEYCODE_U -> "\u0015"
            KeyEvent.KEYCODE_V -> "\u0016"
            KeyEvent.KEYCODE_W -> "\u0017"
            KeyEvent.KEYCODE_X -> "\u0018"
            KeyEvent.KEYCODE_Y -> "\u0019"
            KeyEvent.KEYCODE_Z -> "\u001A"
            KeyEvent.KEYCODE_SPACE -> "\u0000"
            else -> null
        }
    }
    val ch = event.unicodeChar
    if (ch != 0) return ch.toChar().toString()
    return when (event.keyCode) {
        KeyEvent.KEYCODE_ENTER -> "\r"
        KeyEvent.KEYCODE_DEL -> "\u007F"
        KeyEvent.KEYCODE_TAB -> "\t"
        KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
        KeyEvent.KEYCODE_HOME -> "\u001B[H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
        KeyEvent.KEYCODE_INSERT -> "\u001B[2~"
        KeyEvent.KEYCODE_F1 -> "\u001BOP"
        KeyEvent.KEYCODE_F2 -> "\u001BOQ"
        KeyEvent.KEYCODE_F3 -> "\u001BOR"
        KeyEvent.KEYCODE_F4 -> "\u001BOS"
        KeyEvent.KEYCODE_F5 -> "\u001B[15~"
        KeyEvent.KEYCODE_F6 -> "\u001B[17~"
        KeyEvent.KEYCODE_F7 -> "\u001B[18~"
        KeyEvent.KEYCODE_F8 -> "\u001B[19~"
        KeyEvent.KEYCODE_F9 -> "\u001B[20~"
        KeyEvent.KEYCODE_F10 -> "\u001B[21~"
        KeyEvent.KEYCODE_F11 -> "\u001B[23~"
        KeyEvent.KEYCODE_F12 -> "\u001B[24~"
        else -> null
    }
}
