package com.glassbar.ssh.ui.screen.ssh

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.atomic.AtomicBoolean
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Colors used for terminal defaults and the supplementary keyboard. */
data class TerminalTheme(
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val selection: Int,
    val keyboardBackground: Int,
    val keyboardKeyBackground: Int,
    val keyboardKeyForeground: Int,
) {
    companion object {
        val Light = TerminalTheme(
            foreground = 0xFF1A1A1A.toInt(),
            background = 0xFFFAFAFA.toInt(),
            cursor = 0x99000000.toInt(),
            selection = 0x664A90E2,
            keyboardBackground = 0xFFD1D1D1.toInt(),
            keyboardKeyBackground = 0xFFFFFFFF.toInt(),
            keyboardKeyForeground = 0xFF333333.toInt(),
        )
        val Dark = TerminalTheme(
            foreground = 0xFFE7E7E7.toInt(),
            background = 0xFF101114.toInt(),
            cursor = 0x99FFFFFF.toInt(),
            selection = 0x6672A7FF,
            keyboardBackground = 0xFF22252A.toInt(),
            keyboardKeyBackground = 0xFF353940.toInt(),
            keyboardKeyForeground = 0xFFF2F2F2.toInt(),
        )
    }
}

/**
 * Imperative bridge for toolbar actions outside [TerminalView]. Long-pressing the terminal also
 * exposes Android's native Copy/Paste action mode, so using a controller is optional.
 */
class TerminalController {
    private var copyAction: () -> String? = { null }
    private var pasteClipboardAction: () -> Boolean = { false }
    private var pasteTextAction: (String) -> Unit = {}
    private var clearSelectionAction: () -> Unit = {}
    private var setFontScaleAction: (Float) -> Unit = {}
    private var requestFocusAction: () -> Unit = {}

    fun copySelection(): String? = copyAction()
    fun pasteFromClipboard(): Boolean = pasteClipboardAction()
    fun paste(text: String) = pasteTextAction(text)
    fun clearSelection() = clearSelectionAction()
    fun setFontScale(scale: Float) = setFontScaleAction(scale)
    fun requestFocus() = requestFocusAction()

    internal fun attach(
        copy: () -> String?,
        pasteClipboard: () -> Boolean,
        pasteText: (String) -> Unit,
        clearSelection: () -> Unit,
        setFontScale: (Float) -> Unit,
        requestFocus: () -> Unit,
    ) {
        copyAction = copy
        pasteClipboardAction = pasteClipboard
        pasteTextAction = pasteText
        clearSelectionAction = clearSelection
        setFontScaleAction = setFontScale
        requestFocusAction = requestFocus
    }

    internal fun detach() {
        copyAction = { null }
        pasteClipboardAction = { false }
        pasteTextAction = {}
        clearSelectionAction = {}
        setFontScaleAction = {}
        requestFocusAction = {}
    }
}

@Composable
fun rememberTerminalController(): TerminalController = remember { TerminalController() }

@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    onKeyEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
    onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    focusRequester: FocusRequester = remember { FocusRequester() },
    controller: TerminalController = rememberTerminalController(),
    theme: TerminalTheme = TerminalTheme.Light,
    fontScale: Float = 1f,
    onFontScaleChange: (Float) -> Unit = {},
    onSelectionChange: (TerminalSelection?) -> Unit = {},
    onCopyText: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    val terminalView = remember(context, buffer) { TerminalNativeView(context, buffer) }

    val toggleVirtualKeyboard = {
        showVirtualKeyboard = !showVirtualKeyboard
        if (showVirtualKeyboard) {
            terminalView.requestFocus()
        }
    }

    // Wrap each callback in rememberUpdatedState so the LaunchedEffects below are keyed
    // only on stable objects (terminalView, buffer).  Each new lambda from the caller is
    // captured automatically without restarting the effect.
    val currentOnKeyEvent by rememberUpdatedState(onKeyEvent)
    val currentOnResize by rememberUpdatedState(onResize)
    val currentOnFontScaleChange by rememberUpdatedState(onFontScaleChange)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val currentOnCopyText by rememberUpdatedState(onCopyText)

    LaunchedEffect(terminalView, buffer) {
        terminalView.keyListener = { key ->
            buffer.scrollToBottom()
            currentOnKeyEvent(key)
        }
    }
    LaunchedEffect(terminalView) {
        terminalView.onFocusChanged = { hasFocus ->
            showVirtualKeyboard = hasFocus || showVirtualKeyboard
        }
    }
    LaunchedEffect(terminalView) {
        terminalView.onTerminalSizeChanged = { cols, rows -> currentOnResize(cols, rows) }
    }
    LaunchedEffect(terminalView) {
        terminalView.onFontScaleChanged = { scale -> currentOnFontScaleChange(scale) }
    }
    LaunchedEffect(terminalView) {
        terminalView.onSelectionChanged = { sel -> currentOnSelectionChange(sel) }
    }
    LaunchedEffect(terminalView) {
        terminalView.onTextCopied = { txt -> currentOnCopyText(txt) }
    }
    LaunchedEffect(fontScale, terminalView) {
        terminalView.setFontScale(fontScale, notify = false)
    }

    DisposableEffect(buffer, terminalView) {
        val listener: TerminalBuffer.() -> Unit = { terminalView.onBufferChanged() }
        buffer.addChangeListener(listener)
        onDispose { buffer.removeChangeListener(listener) }
    }
    DisposableEffect(controller, terminalView) {
        controller.attach(
            copy = terminalView::copySelectionToClipboard,
            pasteClipboard = terminalView::pasteFromClipboard,
            pasteText = terminalView::pasteText,
            clearSelection = terminalView::clearSelection,
            setFontScale = { terminalView.setFontScale(it, notify = true) },
            requestFocus = terminalView::focusAndShowKeyboard,
        )
        onDispose { controller.detach() }
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { terminalView },
            update = { it.theme = theme },
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
                theme = theme,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@SuppressLint("ViewConstructor")
private class TerminalNativeView(
    context: Context,
    private val buffer: TerminalBuffer,
) : View(context) {
    var keyListener: (String) -> Unit = {}
    var onFocusChanged: (Boolean) -> Unit = {}
    var onTerminalSizeChanged: (cols: Int, rows: Int) -> Unit = { _, _ -> }
    var onFontScaleChanged: (Float) -> Unit = {}
    var onSelectionChanged: (TerminalSelection?) -> Unit = {}
    var onTextCopied: (String) -> Unit = {}

    var theme: TerminalTheme = TerminalTheme.Light
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val drawClipBounds = Rect()
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val baseTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        16f,
        resources.displayMetrics,
    )
    private var terminalFontScale = 1f
    private var lastColumns = -1
    private var lastRows = -1
    private var lastDeleteSurroundingTime = 0L
    private var scrollFraction = 0f

    // Cached cell geometry — recomputed only in recalculateTerminalSize(), read in onDraw().
    // Avoids calling measureText/fontMetrics on every frame (~120 calls/s at 60 fps).
    private var cachedCellWidth = 1f
    private var cachedCellHeight = 1f
    private var cachedFontBaseline = 0f  // offset from row-top to text baseline

    private var selection: TerminalSelection? = null
    private var selectionAnchor: TerminalPosition? = null
    private var draggingSelection = false
    private var selectionActionMode: ActionMode? = null
    private val renderScheduled = AtomicBoolean(false)

    private var cursorPhaseVisible = true
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val snapshot = buffer.snapshot()
            if (!isCursorVisibleInViewport(snapshot)) return
            cursorPhaseVisible = !cursorPhaseVisible
            invalidateCursor(snapshot)
            mainHandler.postDelayed(this, CURSOR_BLINK_MS)
        }
    }

    private val applyBufferChangeRunnable = Runnable {
        renderScheduled.set(false)
        if (!isAttachedToWindow) return@Runnable
        if (selection != null) clearSelection()
        cursorPhaseVisible = true
        restartCursorBlink()
        invalidate()
    }

    private val enqueueBufferChangeRunnable = Runnable {
        if (!isAttachedToWindow) {
            renderScheduled.set(false)
            return@Runnable
        }
        postOnAnimation(applyBufferChangeRunnable)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val backgroundPaint = Paint()
    private val cursorPaint = Paint()
    private val drawCharacter = CharArray(1)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { _, hasFocus ->
            mainHandler.post { onFocusChanged(hasFocus) }
        }
    }

    fun onBufferChanged() {
        // Coalesce arbitrarily many parser batches into at most one redraw per display frame.
        if (renderScheduled.compareAndSet(false, true)) {
            mainHandler.post(enqueueBufferChangeRunnable)
        }
    }

    fun setFontScale(scale: Float, notify: Boolean) {
        val next = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        if (abs(next - terminalFontScale) < 0.005f) return
        terminalFontScale = next
        recalculateTerminalSize()
        invalidate()
        if (notify) onFontScaleChanged(next)
    }

    fun focusAndShowKeyboard() {
        if (!hasFocus()) requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        post { 
            // Keep the custom soft keyboard visible after a tap or toolbar action.
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true)
            }
        }
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (buffer.snapshot().bracketedPasteMode) {
            keyListener("\u001B[200~$normalized\u001B[201~")
        } else {
            keyListener(normalized.replace('\n', '\r'))
        }
    }

    fun pasteFromClipboard(): Boolean {
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) return false
        pasteText(text)
        return true
    }

    fun copySelectionToClipboard(): String? {
        val current = selection ?: return null
        val text = buffer.textInRange(current)
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal text", text))
        onTextCopied(text)
        selectionActionMode?.finish()
        return text
    }

    fun clearSelection() {
        if (selection == null && selectionActionMode == null) return
        selection = null
        selectionAnchor = null
        draggingSelection = false
        val mode = selectionActionMode
        selectionActionMode = null
        mode?.finish()
        onSelectionChanged(null)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartCursorBlink()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(cursorBlinkRunnable)
        mainHandler.removeCallbacks(enqueueBufferChangeRunnable)
        removeCallbacks(applyBufferChangeRunnable)
        renderScheduled.set(false)
        selectionActionMode?.finish()
        selectionActionMode = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        recalculateTerminalSize()
    }

    private fun recalculateTerminalSize() {
        if (width <= 0 || height <= 0) return
        textPaint.textSize = baseTextSizePx * terminalFontScale
        val charWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val metrics = textPaint.fontMetrics
        val lineHeight = ((metrics.descent - metrics.ascent) * LINE_SPACING).coerceAtLeast(1f)
        val columns = (width / charWidth).toInt().coerceIn(8, 240)
        val rows = (height / lineHeight).toInt().coerceIn(4, 120)
        if (columns == lastColumns && rows == lastRows) return
        lastColumns = columns
        lastRows = rows
        // Update cached geometry so onDraw() can skip re-measuring.
        cachedCellWidth = width.toFloat() / columns
        cachedCellHeight = height.toFloat() / rows
        cachedFontBaseline = (cachedCellHeight - metrics.descent - metrics.ascent) / 2f
        buffer.resize(rows, columns)
        onTerminalSizeChanged(columns, rows)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setFontScale(terminalFontScale * detector.scaleFactor, notify = true)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (draggingSelection) {
                    updateSelection(currentEvent)
                    return true
                }
                scrollFraction -= distanceY * SCROLL_SENSITIVITY
                val lines = scrollFraction.toInt()
                if (lines != 0) {
                    buffer.scrollBy(lines)
                    scrollFraction -= lines.toFloat()
                }
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                clearSelection()
                return performClick()
            }

            override fun onLongPress(event: MotionEvent) {
                val position = positionAt(event.x, event.y) ?: return
                selectionAnchor = position
                selection = TerminalSelection(position, position)
                draggingSelection = true
                onSelectionChanged(selection)
                invalidate()
                showSelectionActionMode()
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                selectWordAt(event.x, event.y)
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)

        if (draggingSelection && event.actionMasked == MotionEvent.ACTION_MOVE) {
            updateSelection(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            draggingSelection = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        // A click always focuses and opens input; it no longer toggles focus off.
        focusAndShowKeyboard()
        return true
    }

    private fun positionAt(x: Float, y: Float): TerminalPosition? {
        val snapshot = buffer.snapshot()
        if (width <= 0 || height <= 0 || snapshot.cols <= 0 || snapshot.rows <= 0) return null
        val cellWidth = width.toFloat() / snapshot.cols
        val cellHeight = height.toFloat() / snapshot.rows
        val column = (x / cellWidth).toInt().coerceIn(0, snapshot.cols - 1)
        val visibleRow = (y / cellHeight).toInt().coerceIn(0, snapshot.rows - 1)
        val row = snapshot.lines[visibleRow]
        val snappedColumn = if (row[column].wideContinuation && column > 0) column - 1 else column
        return TerminalPosition(snapshot.viewportTop + visibleRow, snappedColumn)
    }

    private fun updateSelection(event: MotionEvent) {
        val anchor = selectionAnchor ?: return
        val end = positionAt(event.x, event.y) ?: return
        val next = TerminalSelection(anchor, end)
        if (next == selection) return
        selection = next
        onSelectionChanged(next)
        selectionActionMode?.invalidate()
        invalidate()
    }

    private fun selectWordAt(x: Float, y: Float) {
        val position = positionAt(x, y) ?: return
        val snapshot = buffer.snapshot()
        val visibleRow = position.row - snapshot.viewportTop
        val row = snapshot.lines.getOrNull(visibleRow) ?: return
        var first = position.col.coerceIn(0, row.size - 1)
        var last = first
        fun isWordCell(index: Int): Boolean {
            val cell = row[index]
            return !cell.wideContinuation && !cell.char.isWhitespace()
        }
        if (isWordCell(first)) {
            while (first > 0 && isWordCell(first - 1)) first--
            while (last + 1 < row.size && isWordCell(last + 1)) last++
        }
        selectionAnchor = TerminalPosition(position.row, first)
        selection = TerminalSelection(selectionAnchor!!, TerminalPosition(position.row, last))
        draggingSelection = false
        onSelectionChanged(selection)
        invalidate()
        showSelectionActionMode()
    }

    private fun showSelectionActionMode() {
        if (selectionActionMode != null) return
        selectionActionMode = startActionMode(
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.add(Menu.NONE, MENU_COPY, 0, context.getString(android.R.string.copy))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    menu.add(Menu.NONE, MENU_PASTE, 1, context.getString(android.R.string.paste))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                    menu.add(Menu.NONE, MENU_SELECT_ALL, 2, context.getString(android.R.string.selectAll))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.findItem(MENU_COPY)?.isEnabled = selection != null
                    menu.findItem(MENU_PASTE)?.isEnabled = clipboard.hasPrimaryClip()
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
                    MENU_COPY -> {
                        copySelectionToClipboard(); true
                    }
                    MENU_PASTE -> {
                        pasteFromClipboard(); mode.finish(); true
                    }
                    MENU_SELECT_ALL -> {
                        val snapshot = buffer.snapshot()
                        val selected = TerminalSelection(
                            TerminalPosition(0, 0),
                            TerminalPosition(snapshot.screenTop + snapshot.rows - 1, snapshot.cols - 1),
                        )
                        selectionAnchor = selected.start
                        selection = selected
                        onSelectionChanged(selected)
                        invalidate()
                        true
                    }
                    else -> false
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    selectionActionMode = null
                    if (selection != null) {
                        selection = null
                        selectionAnchor = null
                        onSelectionChanged(null)
                        invalidate()
                    }
                }
            },
            ActionMode.TYPE_FLOATING,
        )
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                runCatching {
                    if (!text.isNullOrEmpty()) {
                        pasteText(text.toString())
                    }
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                runCatching {
                    if (!text.isNullOrEmpty()) {
                        pasteText(text.toString())
                    }
                }
                return true
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                return true
            }

            override fun finishComposingText(): Boolean {
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                runCatching {
                    val count = if (beforeLength <= 0) 1 else beforeLength
                    val now = System.currentTimeMillis()
                    if (now - lastDeleteSurroundingTime > 30) {
                        lastDeleteSurroundingTime = now
                        repeat(count) {
                            keyListener("\u007F")
                        }
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                runCatching {
                    keyListener("\r")
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                runCatching {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        return this@TerminalNativeView.onKeyDown(event.keyCode, event)
                    }
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        runCatching {
            keyEventToString(event)?.let {
                keyListener(it)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        runCatching {
            val snapshot = buffer.snapshot()
            val drawColumns = snapshot.cols.coerceAtLeast(1)
            val drawRows = snapshot.rows.coerceAtLeast(1)

            val cellWidth = if (cachedCellWidth > 0f) cachedCellWidth else width.toFloat() / drawColumns
            val cellHeight = if (cachedCellHeight > 0f) cachedCellHeight else height.toFloat() / drawRows
            val fontBaseline = cachedFontBaseline

            canvas.drawColor(theme.background)
            val normalizedSelection = selection?.normalized()
            canvas.getClipBounds(drawClipBounds)
            val clip = drawClipBounds
            val firstVisibleRow = floor(clip.top / cellHeight).toInt().coerceIn(0, drawRows - 1)
            val lastVisibleRow = floor((clip.bottom - 1).coerceAtLeast(0) / cellHeight)
                .toInt()
                .coerceIn(firstVisibleRow, drawRows - 1)
            val firstVisibleColumn = (floor(clip.left / cellWidth).toInt() - 1)
                .coerceIn(0, drawColumns - 1)
            val lastVisibleColumn = floor((clip.right - 1).coerceAtLeast(0) / cellWidth)
                .toInt()
                .coerceIn(firstVisibleColumn, drawColumns - 1)
            for (visibleRow in firstVisibleRow..lastVisibleRow) {
                if (visibleRow >= snapshot.lines.size) break
                val line = snapshot.lines[visibleRow]
                val rowTop = visibleRow * cellHeight
                if (rowTop >= height) break
                val absoluteRow = snapshot.viewportTop + visibleRow
                val finalColumn = minOf(line.size - 1, lastVisibleColumn)
                for (column in firstVisibleColumn..finalColumn) {
                    if (column >= line.size) break
                    val cell = line[column]
                    val left = column * cellWidth
                    val background = when {
                        isSelected(normalizedSelection, absoluteRow, column) ||
                            (cell.wideContinuation && isSelected(
                                normalizedSelection,
                                absoluteRow,
                                column - 1,
                            )) -> theme.selection
                        cell.inverse -> if (cell.fg == TerminalColors.DEFAULT_FG) {
                            theme.foreground
                        } else {
                            TerminalColors.fg(cell.fg)
                        }
                        cell.bg != TerminalColors.DEFAULT_BG -> TerminalColors.bg(cell.bg)
                        else -> null
                    }
                    if (background != null) {
                        backgroundPaint.color = background
                        canvas.drawRect(left, rowTop, left + cellWidth, rowTop + cellHeight, backgroundPaint)
                    }

                    if (cell.char == ' ' || cell.wideContinuation) continue
                    textPaint.color = if (cell.inverse) {
                        if (cell.bg == TerminalColors.DEFAULT_BG) theme.background else TerminalColors.bg(cell.bg)
                    } else if (cell.fg == TerminalColors.DEFAULT_FG) {
                        theme.foreground
                    } else {
                        TerminalColors.fg(cell.fg)
                    }
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    val baseline = rowTop + fontBaseline
                    drawCharacter[0] = cell.char
                    canvas.drawText(drawCharacter, 0, 1, left, baseline, textPaint)
                }
            }

            val visibleCursorRow = snapshot.cursorRow - snapshot.viewportTop
            if (
                cursorPhaseVisible && snapshot.cursorVisible &&
                visibleCursorRow in 0 until drawRows
            ) {
                val visibleCursorColumn = snapshot.cursorCol.coerceIn(0, drawColumns - 1)
                cursorPaint.color = theme.cursor
                canvas.drawRect(
                    visibleCursorColumn * cellWidth,
                    visibleCursorRow * cellHeight,
                    (visibleCursorColumn + 1) * cellWidth,
                    (visibleCursorRow + 1) * cellHeight,
                    cursorPaint,
                )
            }
        }
    }

    private fun restartCursorBlink() {
        mainHandler.removeCallbacks(cursorBlinkRunnable)
        cursorPhaseVisible = true
        if (isAttachedToWindow && isCursorVisibleInViewport(buffer.snapshot())) {
            mainHandler.postDelayed(cursorBlinkRunnable, CURSOR_BLINK_MS)
        }
    }

    private fun isCursorVisibleInViewport(snapshot: TerminalSnapshot): Boolean =
        snapshot.cursorVisible && snapshot.cursorRow - snapshot.viewportTop in 0 until snapshot.rows

    private fun invalidateCursor(snapshot: TerminalSnapshot) {
        if (width <= 0 || height <= 0 || !isCursorVisibleInViewport(snapshot)) return
        val cellWidth = width.toFloat() / snapshot.cols.coerceAtLeast(1)
        val cellHeight = height.toFloat() / snapshot.rows.coerceAtLeast(1)
        val column = snapshot.cursorCol.coerceIn(0, snapshot.cols - 1)
        val row = snapshot.cursorRow - snapshot.viewportTop
        postInvalidateOnAnimation(
            floor(column * cellWidth).toInt(),
            floor(row * cellHeight).toInt(),
            ceil((column + 1) * cellWidth).toInt() + 1,
            ceil((row + 1) * cellHeight).toInt() + 1,
        )
    }

    private fun isSelected(selection: TerminalSelection?, row: Int, col: Int): Boolean {
        selection ?: return false
        if (row !in selection.start.row..selection.end.row) return false
        val firstColumn = if (row == selection.start.row) selection.start.col else 0
        val lastColumn = if (row == selection.end.row) selection.end.col else Int.MAX_VALUE
        return col in firstColumn..lastColumn
    }

    private companion object {
        const val CURSOR_BLINK_MS = 530L
        const val LINE_SPACING = 1.12f
        const val SCROLL_SENSITIVITY = 0.4f
        const val MIN_FONT_SCALE = 0.65f
        const val MAX_FONT_SCALE = 2.25f
        const val MENU_COPY = 1
        const val MENU_PASTE = 2
        const val MENU_SELECT_ALL = 3
    }
}

private fun keyEventToString(event: KeyEvent): String? {
    val baseSequence = getBaseKeySequence(event) ?: return null
    return if (event.isAltPressed && !baseSequence.startsWith("\u001B")) {
        "\u001B$baseSequence"
    } else {
        baseSequence
    }
}

private fun getBaseKeySequence(event: KeyEvent): String? {
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
    val character = event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK.inv()
    if (character != 0 && Character.isValidCodePoint(character)) {
        return String(Character.toChars(character))
    }
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
