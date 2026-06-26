package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    onKeyEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val context = LocalContext.current
    var cursorTick by remember { mutableIntStateOf(0) }

    val terminalView = remember {
        TerminalNativeView(context, buffer).also {
            it.keyListener = onKeyEvent
        }
    }

    LaunchedEffect(buffer) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        buffer.addChangeListener {
            handler.post { terminalView.invalidate() }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            cursorTick++
            terminalView.invalidate()
        }
    }

    LaunchedEffect(focusRequester) {
        delay(200)
        terminalView.requestFocus()
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { terminalView },
            modifier = Modifier.weight(1f).fillMaxSize().padding(3.dp),
        )
        VirtualKeyboard(
            onKey = onKeyEvent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class TerminalNativeView(
    context: Context,
    private val buffer: TerminalBuffer,
) : View(context) {

    var keyListener: (String) -> Unit = {}

    private var lastDeleteSurroundingTime = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, hasFocus ->
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            if (hasFocus) {
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm.hideSoftInputFromWindow(windowToken, 0)
            }
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val lineHeight = textPaint.textSize
            val lines = if (lineHeight > 0) (dy / lineHeight).toInt() else 0
            if (lines != 0) buffer.scrollBy(lines)
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(this@TerminalNativeView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
                    // Exclude newline — handled by sendKeyEvent for Enter
                    val str = it.toString().replace("\n", "")
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
                // Other special keys (unicodeChar == 0)
                if (event.unicodeChar == 0) {
                    val str = keyEventToString(event)
                    if (str != null) keyListener(str)
                }
                return true
            }
            override fun performEditorAction(actionCode: Int): Boolean {
                if (actionCode == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
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

        textPaint.textSize = 41f
        // 先测量单字符宽度，再计算能容纳的列数；然后基于列数均分画布宽度，避免列间空隙
        val measuredCharW = textPaint.measureText("M")
        val measuredCellH = textPaint.textSize
        var tentativeCols = (canvasWidth / measuredCharW).toInt().coerceAtLeast(1)
        // 不需要超过缓冲列数（但至少 1 列）
        val drawCols = tentativeCols.coerceAtMost(buffer.cols)
        val cellW = canvasWidth / drawCols
        val cellH = measuredCellH
        val visibleRows = buffer.visibleRows()
        val maxVisualRows = buffer.rows

        for (r in visibleRows.indices) {
            val row = visibleRows[r]
            for (c in row.indices) {
                val cell = row[c]
                if (cell.char == ' ') continue

                val wrappedRowOffset = c / drawCols
                val visRow = r + wrappedRowOffset
                if (visRow >= maxVisualRows) break
                val wrappedCol = c % drawCols

                val x = wrappedCol * cellW
                val baseline = visRow * cellH - textPaint.ascent()

                if (cell.bg != TerminalColors.DEFAULT_BG) {
                    bgPaint.color = TerminalColors.bg(cell.bg)
                    canvas.drawRect(wrappedCol * cellW, visRow * cellH, (wrappedCol + 1) * cellW, (visRow + 1) * cellH, bgPaint)
                }

                val ansiFg = if (cell.inverse) cell.bg else cell.fg
                textPaint.color = if (ansiFg == TerminalColors.DEFAULT_FG) TermFg
                else if (ansiFg == 0) TermFg
                else TerminalColors.fg(ansiFg)
                textPaint.isFakeBoldText = cell.bold
                textPaint.isUnderlineText = cell.underline

                canvas.drawText(cell.char.toString(), x, baseline, textPaint)
            }
        }

        val curCol = buffer.cursorCol
        val curRow = buffer.cursorRow - buffer.scrollTop
        val times = System.currentTimeMillis() % 1060
        if (times < 530 && buffer.cursorVisible) {
            val drawColsForCursor = (canvasWidth / cellW).toInt().coerceAtLeast(1)
            val cursorRowOffset = curCol / drawColsForCursor
            val cursorVisRow = curRow + cursorRowOffset
            val cursorVisCol = curCol % drawColsForCursor
            if (cursorVisRow in 0 until buffer.rows && cursorVisCol in 0 until drawColsForCursor) {
                cursorPaint.color = android.graphics.Color.argb(100, 0, 0, 0)
                canvas.drawRect(cursorVisCol * cellW, cursorVisRow * cellH,
                    (cursorVisCol + 1) * cellW, (cursorVisRow + 1) * cellH, cursorPaint)
            }
        }
    }
}

private fun keyEventToString(event: KeyEvent): String? {
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
        else -> when {
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_C -> "\u0003"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_D -> "\u0004"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z -> "\u001A"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_A -> "\u0001"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_E -> "\u0005"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_L -> "\u000C"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_U -> "\u0015"
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_W -> "\u0017"
            else -> null
        }
    }
}
