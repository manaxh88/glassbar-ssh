package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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

private data class VirtKey(val label: String, val code: String, val wide: Boolean = false)

@Composable
fun VirtualKeyboard(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys = listOf(
        listOf(
            VirtKey("Esc", "\u001B"),
            VirtKey("Tab", "\t"),
            VirtKey("Ctrl", ""),
            VirtKey("/", "/"),
            VirtKey("-", "-"),
            VirtKey("|", "|"),
        ),
        listOf(
            VirtKey("↑", "\u001B[A"),
            VirtKey("↓", "\u001B[B"),
            VirtKey("←", "\u001B[D"),
            VirtKey("→", "\u001B[C"),
            VirtKey("↵", "\r", wide = true),
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFE8E8E8))
            .padding(4.dp),
    ) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            keys.forEach { rowKeys ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    rowKeys.forEach { key ->
                        val weight = if (key.wide) 1.5f else 1f
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .height(36.dp)
                                .background(Color.White, RoundedCornerShape(6.dp))
                                .clickable { onKey(key.code) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF333333),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
