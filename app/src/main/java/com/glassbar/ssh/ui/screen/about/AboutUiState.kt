package com.glassbar.ssh.ui.screen.about

import androidx.compose.runtime.Immutable

@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
)

@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
)
