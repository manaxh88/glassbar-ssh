package com.glassbar.ssh.ui.navigation3

import android.os.Parcelable
import androidx.navigation3.runtime.NavKey
import kotlinx.parcelize.Parcelize

sealed interface Route : NavKey, Parcelable {
    @Parcelize
    data object Main : Route

    @Parcelize
    data object About : Route
}
