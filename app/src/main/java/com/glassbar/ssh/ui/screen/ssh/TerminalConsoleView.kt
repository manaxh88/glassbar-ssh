package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalConsoleView(
    terminalState: TerminalState,
    isDark: Boolean,
    fontScale: Float,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    topPadding: Dp = 0.dp,
) {
    val lines by terminalState.textLines.collectAsStateWithLifecycle()
    val cursorPosition by terminalState.cursorPosition.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()
    val terminalInputView = remember { mutableStateOf<TerminalInputView?>(null) }

    // Blinking Block Cursor Animation
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )

    LaunchedEffect(isDark) {
        terminalState.isDarkTheme = isDark
    }

    val isAltScreen by terminalState.isAltScreen.collectAsStateWithLifecycle()

    // Auto-scroll to cursor when new log lines arrive or font scale changes
    LaunchedEffect(lines.size, fontScale) {
        if (lines.isNotEmpty() && !isAltScreen) {
            listState.animateScrollToItem(cursorPosition.second)
        }
    }

    // Auto-scroll to cursor when keyboard (IME) opens
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && lines.isNotEmpty() && !isAltScreen) {
            listState.animateScrollToItem(cursorPosition.second)
        }
    }

    // Request focus for keyboard on launch
    LaunchedEffect(Unit) {
        terminalInputView.value?.showSoftKeyboard()
    }

    val bgColor = MiuixTheme.colorScheme.surfaceContainer
    val textColor = if (isDark) Color(0xFFE7E7E7) else Color(0xFF1A1A1A)
    val cursorColor = if (isDark) Color(0xFF50FA7B) else Color(0xFF2E7D32)
    val cursorTextColor = if (isDark) Color.Black else Color.White
    val baseFontSize = (14 * fontScale).sp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        // Output Logs Area - Tapping anywhere opens soft keyboard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    terminalInputView.value?.showSoftKeyboard()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Invisible input bridge for system soft keyboard & physical keyboard
            AndroidView(
                factory = { context ->
                    TerminalInputView(context).apply {
                        terminalInputView.value = this
                    }
                },
                update = { view ->
                    view.onSendInput = onSendInput
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0.01f)
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                // We use 0.6f as a safe multiplier for monospaced fonts to ensure we don't 
                // overestimate columns. Overestimating causes Compose to soft-wrap the text or clip it.
                val charWidthDp = with(density) { (baseFontSize.toPx() * 0.6f).toDp() }
                val charHeightDp = with(density) { (18 * fontScale).sp.toDp() }

                val availableWidth = maxWidth
                // We subtract topPadding and bottomPadding to get the actual viewable terminal height
                val availableHeight = maxHeight - topPadding - bottomPadding

                val newCols = maxOf(10, minOf(500, (availableWidth / charWidthDp).toInt()))
                val newRows = maxOf(5, minOf(500, (availableHeight / charHeightDp).toInt()))

                LaunchedEffect(newCols, newRows) {
                    terminalState.resize(newCols, newRows)
                }

                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
                        contentPadding = PaddingValues(top = topPadding),
                    ) {
                        itemsIndexed(lines) { index, line ->
                            val lineText = if (index == cursorPosition.second) {
                                val cx = cursorPosition.first
                                buildAnnotatedString {
                                    val textLen = line.length
                                    if (cx >= textLen) {
                                        append(line)
                                        append(" ".repeat(cx - textLen))
                                        withStyle(SpanStyle(background = cursorColor.copy(alpha = cursorAlpha))) {
                                            append(" ")
                                        }
                                    } else {
                                        append(line.subSequence(0, cx))
                                        withStyle(SpanStyle(background = cursorColor.copy(alpha = cursorAlpha), color = cursorTextColor)) {
                                            append(line.subSequence(cx, cx + 1))
                                        }
                                        append(line.subSequence(cx + 1, textLen))
                                    }
                                }
                            } else {
                                line
                            }
                            Text(
                                text = lineText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseFontSize,
                                color = textColor,
                                lineHeight = (18 * fontScale).sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
