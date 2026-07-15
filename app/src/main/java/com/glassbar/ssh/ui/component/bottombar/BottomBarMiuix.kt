package com.glassbar.ssh.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbar.ssh.R
import com.glassbar.ssh.ui.LocalMainPagerState
import com.glassbar.ssh.ui.component.FloatingBottomBar
import com.glassbar.ssh.ui.component.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class BarItem(val label: String, val icon: ImageVector)

@Composable
fun BottomBarMiuix(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    modifier: Modifier,
) {
    val mainState = LocalMainPagerState.current

    val items = BottomBarDestination.entries.map { destination ->
        BarItem(
            label = stringResource(destination.label),
            icon = destination.icon,
        )
    }

    FloatingBottomBar(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        selectedIndex = { mainState.selectedPage },
        onSelected = { mainState.animateToPage(it) },
        backdrop = backdrop,
        tabsCount = items.size,
        isBlurEnabled = true,
    ) {
        items.forEachIndexed { index, item ->
            FloatingBottomBarItem(
                onClick = {
                    mainState.animateToPage(index)
                },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}

enum class BottomBarDestination(
    @get:StringRes val label: Int,
    val icon: ImageVector,
) {
    Servers(R.string.servers, Icons.Rounded.Dns),
    Terminal(R.string.terminal, Icons.Rounded.Terminal),
}
