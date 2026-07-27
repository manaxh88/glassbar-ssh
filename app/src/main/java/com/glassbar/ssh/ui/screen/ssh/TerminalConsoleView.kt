package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

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
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(isDark) {
        terminalState.isDarkTheme = isDark
    }

    // Auto-scroll to bottom when new log lines arrive
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    val bgColor = if (isDark) Color(0xFF101114) else Color(0xFFFAFAFA)
    val textColor = if (isDark) Color(0xFFE7E7E7) else Color(0xFF1A1A1A)
    val promptColor = if (isDark) Color(0xFF4CAF50) else Color(0xFF2E7D32)
    val baseFontSize = (14 * fontScale).sp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        // Output Logs Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
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
            },
            isDark = isDark,
        )

        // Interactive Command Input Field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDark) Color(0xFF181A1F) else Color(0xFFF0F0F3))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$ ",
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = promptColor,
            )
            Spacer(Modifier.width(4.dp))
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                label = "输入 SSH 命令...",
                useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotEmpty()) {
                            onSendInput(inputText + "\r")
                            inputText = ""
                        }
                    },
                ),
            )
        }
    }
}
