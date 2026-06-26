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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

private data class VKey(val label: String, val code: String, val wide: Float = 1f)

@Composable
fun VirtualKeyboard(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyH = 42.dp
    val keySpacing = 3.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFD1D1D1))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(keySpacing),
    ) {
        // Row 1: q w e r t y u i o p
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
            "q w e r t y u i o p".split(" ").forEach {
                Key(it, it, keyH, 1f, onKey)
            }
        }
        // Row 2: a s d f g h j k l
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
            Box(modifier = Modifier.weight(0.5f).height(keyH))
            "a s d f g h j k l".split(" ").forEach {
                Key(it, it, keyH, 1f, onKey)
            }
            Box(modifier = Modifier.weight(0.5f).height(keyH))
        }
        // Row 3: Shift z x c v b n m Backspace
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
            Key("⇧", "", keyH, 1.2f, onKey)
            "z x c v b n m".split(" ").forEach {
                Key(it, it, keyH, 1f, onKey)
            }
            Key("⌫", "\u007F", keyH, 1.2f, onKey)
        }
        // Row 4: 123 Ctrl Alt Space . Enter
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
            Key("123", "", keyH, 1f, onKey)
            Key("Ctrl", "", keyH, 1f, onKey)
            Key("Alt", "", keyH, 1f, onKey)
            Key(",", ",", keyH, 1f, onKey)
            Key(" ", " ", keyH, 3f, onKey)
            Key(".", ".", keyH, 1f, onKey)
            Key("↵", "\r", keyH, 2f, onKey)
        }
        // Row 5: Esc Tab / - | ↑ ↓ ← →
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keySpacing)) {
            Key("Esc", "\u001B", keyH, 1f, onKey)
            Key("Tab", "\t", keyH, 1f, onKey)
            Key("/", "/", keyH, 1f, onKey)
            Key("-", "-", keyH, 1f, onKey)
            Key("|", "|", keyH, 1f, onKey)
            Key("↑", "\u001B[A", keyH, 1f, onKey, isSpecial = true)
            Key("↓", "\u001B[B", keyH, 1f, onKey, isSpecial = true)
            Key("←", "\u001B[D", keyH, 1f, onKey, isSpecial = true)
            Key("→", "\u001B[C", keyH, 1f, onKey, isSpecial = true)
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    code: String,
    height: androidx.compose.ui.unit.Dp,
    weight: Float,
    onKey: (String) -> Unit,
    isSpecial: Boolean = false,
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(height)
            .background(
                if (isSpecial) Color(0xFFB0B0B0) else Color.White,
                RoundedCornerShape(6.dp)
            )
            .clickable { if (code.isNotEmpty()) onKey(code) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (label.length <= 2) 14.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF333333),
            textAlign = TextAlign.Center,
        )
    }
}

