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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun TerminalConsoleView(
    terminalState: TerminalState,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    isDark: Boolean = true,
    bottomPadding: Dp = 0.dp,
    topPadding: Dp = 0.dp,
) {
    val lines by terminalState.textLines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
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

    // Auto-scroll to bottom when new log lines arrive or font scale changes
    LaunchedEffect(lines.size, fontScale) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    // Auto-scroll to bottom when keyboard (IME) opens
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    // Request focus for keyboard on launch
    LaunchedEffect(Unit) {
        terminalInputView.value?.showSoftKeyboard()
    }

    val bgColor = MiuixTheme.colorScheme.surfaceContainer
    val textColor = if (isDark) Color(0xFFE7E7E7) else Color(0xFF1A1A1A)
    val cursorColor = if (isDark) Color(0xFF50FA7B) else Color(0xFF2E7D32)
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

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = cursorColor.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold)) {
                                        append("▋")
                                    }
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseFontSize,
                                lineHeight = (18 * fontScale).sp,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        itemsIndexed(lines) { index, line ->
                            val lineText = if (index == lines.size - 1) {
                                buildAnnotatedString {
                                    append(line)
                                    withStyle(SpanStyle(color = cursorColor.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold)) {
                                        append("▋")
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
