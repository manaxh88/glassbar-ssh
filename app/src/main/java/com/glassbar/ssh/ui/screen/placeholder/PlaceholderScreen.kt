package com.glassbar.ssh.ui.screen.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class PlaceholderPage(
    val title: String,
    val icon: ImageVector,
) {
    Home("Home", Icons.Rounded.Cottage),
    SuperUser("SuperUser", Icons.Rounded.Security),
    Module("Module", Icons.Rounded.Extension),
    Settings("Settings", Icons.Rounded.Settings),
}

@Composable
fun PlaceholderScreen(
    page: PlaceholderPage,
    bottomPadding: Dp = 0.dp,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .background(MiuixTheme.colorScheme.surfaceContainer),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = page.title,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = page.title,
            fontSize = 24.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = "Glass Bottom Bar Demo",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
