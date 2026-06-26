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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

private data class VKey(val label: String, val code: String, val wide: Float = 1f)

@Composable
fun VirtualKeyboard(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyH = 36.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFD1D1D1))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolKey("Esc", "\u001B", keyH, 1f, onKey)
            ToolKey("Tab", "\t", keyH, 1f, onKey)
            ToolKey("Ctrl", "", keyH, 1f, onKey)
            ToolKey("/", "/", keyH, 1f, onKey)
            ToolKey("-", "-", keyH, 1f, onKey)
            ToolKey("|", "|", keyH, 1f, onKey)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ToolKey("↑", "\u001B[A", keyH, 1f, onKey)
            ToolKey("↓", "\u001B[B", keyH, 1f, onKey)
            ToolKey("←", "\u001B[D", keyH, 1f, onKey)
            ToolKey("→", "\u001B[C", keyH, 1f, onKey)
            ToolKey("Tab", "\t", keyH, 1f, onKey)
            ToolKey("↵", "\r", keyH, 2f, onKey)
        }
    }
}

@Composable
private fun RowScope.ToolKey(
    label: String, code: String, height: androidx.compose.ui.unit.Dp,
    weight: Float, onKey: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(weight).height(height)
            .background(Color.White, RoundedCornerShape(6.dp))
            .clickable { if (code.isNotEmpty()) onKey(code) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF333333), textAlign = TextAlign.Center)
    }
}
