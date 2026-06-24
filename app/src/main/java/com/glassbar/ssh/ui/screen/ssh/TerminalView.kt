package com.glassbar.ssh.ui.screen.ssh

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
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

    Box(
        modifier = modifier
            .clickable { terminalView.requestFocus() }
    ) {
        AndroidView(
            factory = { terminalView },
            modifier = Modifier.fillMaxSize().padding(3.dp),
        )
    }
}

private class TerminalNativeView(
    context: Context,
    private val buffer: TerminalBuffer,
) : View(context) {

    var keyListener: (String) -> Unit = {}

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

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.let {
                    val str = it.toString().replace("\n", "\r")
                    keyListener(str)
                }
                return super.commitText(text, newCursorPosition)
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // TYPE_NULL prevents composing; if reached, don't forward to avoid duplicate chars
                return super.setComposingText(text, newCursorPosition)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Handled by sendKeyEvent for KEYCODE_DEL
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // Handle all special keys including Delete via KeyEvent
                if (event.action == KeyEvent.ACTION_DOWN && event.unicodeChar == 0) {
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

        textPaint.textSize = 36f
        var cellW = textPaint.measureText("M")
        if (cellW * buffer.cols > canvasWidth) {
            textPaint.textSize = (canvasWidth / buffer.cols) * 0.96f
            cellW = textPaint.measureText("M")
        }
        val cellH = textPaint.textSize * 1.05f

        val visibleRows = buffer.visibleRows()
        for (r in visibleRows.indices) {
            val row = visibleRows[r]
            for (c in row.indices) {
                val cell = row[c]
                if (cell.char == ' ') continue

                val x = c * cellW
                val baseline = r * cellH - textPaint.ascent() * 0.95f

                if (cell.bg != TerminalColors.DEFAULT_BG) {
                    bgPaint.color = TerminalColors.bg(cell.bg)
                    canvas.drawRect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH, bgPaint)
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
        if (times < 530 && buffer.cursorVisible &&
            curRow in 0 until buffer.rows && curCol in 0 until buffer.cols
        ) {
            cursorPaint.color = android.graphics.Color.argb(100, 0, 0, 0)
            canvas.drawRect(curCol * cellW, curRow * cellH,
                (curCol + 1) * cellW, (curRow + 1) * cellH, cursorPaint)
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
