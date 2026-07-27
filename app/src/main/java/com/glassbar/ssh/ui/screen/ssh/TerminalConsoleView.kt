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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Text

private const val DUMMY_INPUT_BUFFER = " "

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
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length)))
    }

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
                    focusRequester.requestFocus()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Invisible input bridge for system soft keyboard & physical keyboard
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    val oldText = textFieldValue.text
                    val newText = newValue.text
                    
                    if (newText.length > oldText.length) {
                        // Character(s) added
                        val added = newText.substring(oldText.length)
                        onSendInput(added)
                        textFieldValue = newValue
                    } else if (newText.length < oldText.length) {
                        // Character(s) deleted
                        val deletedCount = oldText.length - newText.length
                        repeat(deletedCount) {
                            onSendInput("\u007F") // Standard SSH DEL
                        }
                        
                        // If they deleted our dummy character, reset it so backspace still works next time
                        if (newText.isEmpty()) {
                            textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                        } else {
                            textFieldValue = newValue
                        }
                    } else if (newText != oldText) {
                        // Text changed but length is same (e.g. composition replace)
                        textFieldValue = newValue
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
                                    textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                                    true
                                }
                                Key.Backspace -> {
                                    onSendInput("\u007F")
                                    val current = textFieldValue.text
                                    if (current.length > 1) {
                                        textFieldValue = TextFieldValue(current.dropLast(1), TextRange(current.length - 1))
                                    } else {
                                        textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                                    }
                                    true
                                }
                                Key.Delete -> {
                                    onSendInput("\u001B[3~")
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
                    imeAction = ImeAction.Send,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        onSendInput("\r")
                        textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                    },
                ),
            )

            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
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

        // Quick Keys Bar
        TerminalQuickKeys(
            onKey = { key ->
                onSendInput(key)
                if (key == "\r") {
                    textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                } else if (key == "\u007F") {
                    val current = textFieldValue.text
                    if (current.length > 1) {
                        textFieldValue = TextFieldValue(current.dropLast(1), TextRange(current.length - 1))
                    } else {
                        textFieldValue = TextFieldValue(DUMMY_INPUT_BUFFER, TextRange(DUMMY_INPUT_BUFFER.length))
                    }
                }
                focusRequester.requestFocus()
            },
            isDark = isDark,
        )
    }
}
