package com.glassbar.ssh.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.glassbar.ssh.ui.UiMode
import com.glassbar.ssh.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float = 1f,
    val uiMode: UiMode = UiMode.Miuix,
)
