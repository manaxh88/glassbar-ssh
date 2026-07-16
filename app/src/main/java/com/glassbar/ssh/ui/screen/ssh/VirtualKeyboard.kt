package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun VirtualKeyboard(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    theme: TerminalTheme = TerminalTheme.Light,
) {
    val keyHeight = 36.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(theme.keyboardBackground))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolKey("Esc", "\u001B", keyHeight, 1f, theme, onKey)
            ToolKey("Tab", "\t", keyHeight, 1f, theme, onKey)
            ToolKey("/", "/", keyHeight, 1f, theme, onKey)
            ToolKey("-", "-", keyHeight, 1f, theme, onKey)
            ToolKey("|", "|", keyHeight, 1f, theme, onKey)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolKey("↑", "\u001B[A", keyHeight, 1f, theme, onKey)
            ToolKey("↓", "\u001B[B", keyHeight, 1f, theme, onKey)
            ToolKey("←", "\u001B[D", keyHeight, 1f, theme, onKey)
            ToolKey("→", "\u001B[C", keyHeight, 1f, theme, onKey)
            ToolKey("Enter", "\r", keyHeight, 2f, theme, onKey)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolKey("Ctrl+C", "\u0003", keyHeight, 1f, theme, onKey)
            ToolKey("Ctrl+D", "\u0004", keyHeight, 1f, theme, onKey)
            ToolKey("Ctrl+Z", "\u001A", keyHeight, 1f, theme, onKey)
            ToolKey("Ctrl+L", "\u000C", keyHeight, 1f, theme, onKey)
        }
    }
}

@Composable
private fun RowScope.ToolKey(
    label: String,
    code: String,
    height: Dp,
    weight: Float,
    theme: TerminalTheme,
    onKey: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height)
            .background(Color(theme.keyboardKeyBackground), RoundedCornerShape(6.dp))
            .clickable { onKey(code) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(theme.keyboardKeyForeground),
            textAlign = TextAlign.Center,
        )
    }
}
