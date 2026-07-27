package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
fun TerminalQuickKeys(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = true,
) {
    val keyHeight = 38.dp
    val bgColor = if (isDark) Color(0xFF1E1E24) else Color(0xFFE0E0E6)
    val keyBgColor = if (isDark) Color(0xFF2E2E38) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFF0F0F5) else Color(0xFF222226)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickKey("Esc", "\u001B", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Tab", "\t", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Ctrl+C", "\u0003", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Ctrl+D", "\u0004", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Ctrl+Z", "\u001A", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Ctrl+L", "\u000C", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("↑", "\u001B[A", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("↓", "\u001B[B", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("←", "\u001B[D", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("→", "\u001B[C", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("/", "/", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("-", "-", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("|", "|", keyHeight, keyBgColor, textColor, onKey)
        QuickKey("Enter", "\r", keyHeight, keyBgColor, textColor, onKey)
    }
}

@Composable
private fun QuickKey(
    label: String,
    code: String,
    height: Dp,
    bgColor: Color,
    textColor: Color,
    onKey: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .height(height)
            .background(bgColor, RoundedCornerShape(6.dp))
            .clickable { onKey(code) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}
