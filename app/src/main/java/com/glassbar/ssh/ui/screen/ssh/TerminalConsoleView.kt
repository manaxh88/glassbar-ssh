package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun TerminalConsoleView(
    terminalState: TerminalState,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    isDark: Boolean = true,
) {
    val lines by terminalState.textLines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isDark) {
        terminalState.isDarkTheme = isDark
    }

    // Auto-scroll to bottom when new log lines arrive
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    // Request focus for keyboard on launch
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val bgColor = if (isDark) Color(0xFF101114) else Color(0xFFFAFAFA)
    val textColor = if (isDark) Color(0xFFE7E7E7) else Color(0xFF1A1A1A)
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
                    focusRequester.requestFocus()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Invisible input bridge for system soft keyboard & physical keyboard
            BasicTextField(
                value = "",
                onValueChange = { newValue ->
                    if (newValue.isNotEmpty()) {
                        onSendInput(newValue)
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.Enter -> {
                                    onSendInput("\r")
                                    true
                                }
                                Key.Backspace -> {
                                    onSendInput("\u007F")
                                    true
                                }
                                Key.Tab -> {
                                    onSendInput("\t")
                                    true
                                }
                                Key.DirectionUp -> {
                                    onSendInput("\u001B[A")
                                    true
                                }
                                Key.DirectionDown -> {
                                    onSendInput("\u001B[B")
                                    true
                                }
                                Key.DirectionLeft -> {
                                    onSendInput("\u001B[D")
                                    true
                                }
                                Key.DirectionRight -> {
                                    onSendInput("\u001B[C")
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Unspecified,
                    autoCorrectEnabled = false,
                ),
            )

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line,
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

        // Quick Keys Bar
        TerminalQuickKeys(
            onKey = { key ->
                onSendInput(key)
                focusRequester.requestFocus()
            },
            isDark = isDark,
        )
    }
}
