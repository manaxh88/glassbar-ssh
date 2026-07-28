package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VirtualKeysView(
    modifier: Modifier = Modifier,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKeyPress: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VirtualKey(text = "ESC", modifier = Modifier.weight(1f)) { onKeyPress("\u001b") }
            VirtualKey(text = "/", modifier = Modifier.weight(1f)) { onKeyPress("/") }
            VirtualKey(text = "-", modifier = Modifier.weight(1f)) { onKeyPress("-") }
            VirtualKey(text = "HOME", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[1~") }
            VirtualKey(text = "↑", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[A") }
            VirtualKey(text = "END", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[4~") }
            VirtualKey(text = "PGUP", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[5~") }
        }

        // Row 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VirtualKey(
                text = "CTRL",
                isActive = ctrlActive,
                modifier = Modifier.weight(1f)
            ) { onCtrlToggle() }
            VirtualKey(
                text = "ALT",
                isActive = altActive,
                modifier = Modifier.weight(1f)
            ) { onAltToggle() }
            VirtualKey(text = "TAB", modifier = Modifier.weight(1f)) { onKeyPress("\t") }
            VirtualKey(text = "←", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[D") }
            VirtualKey(text = "↓", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[B") }
            VirtualKey(text = "→", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[C") }
            VirtualKey(text = "PGDN", modifier = Modifier.weight(1f)) { onKeyPress("\u001b[6~") }
        }
    }
}

@Composable
private fun VirtualKey(
    text: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val textColor = if (isActive) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
