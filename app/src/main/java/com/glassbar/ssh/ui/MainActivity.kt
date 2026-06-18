package com.glassbar.ssh.ui

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.glassbar.ssh.ui.component.bottombar.BottomBar
import com.glassbar.ssh.ui.component.bottombar.MainPagerState
import com.glassbar.ssh.ui.component.bottombar.rememberMainPagerState
import com.glassbar.ssh.ui.navigation3.LocalNavigator
import com.glassbar.ssh.ui.navigation3.Navigator
import com.glassbar.ssh.ui.navigation3.Route
import com.glassbar.ssh.ui.navigation3.rememberNavigator
import com.glassbar.ssh.ui.screen.about.AboutScreen
import com.glassbar.ssh.ui.screen.ssh.HomeScreen
import com.glassbar.ssh.ui.screen.ssh.SshConnectionInfo
import com.glassbar.ssh.ui.screen.placeholder.PlaceholderPage
import com.glassbar.ssh.ui.screen.placeholder.PlaceholderScreen
import com.glassbar.ssh.ui.screen.ssh.SshScreen
import com.glassbar.ssh.ui.theme.KernelSUTheme
import com.glassbar.ssh.ui.theme.LocalColorMode
import com.glassbar.ssh.ui.theme.LocalEnableBlur
import com.glassbar.ssh.ui.theme.LocalEnableFloatingBottomBar
import com.glassbar.ssh.ui.theme.LocalEnableFloatingBottomBarBlur
import com.glassbar.ssh.ui.util.rememberBlurBackdrop
import com.glassbar.ssh.ui.util.rememberContentReady
import com.glassbar.ssh.ui.viewmodel.MainActivityViewModel
import com.glassbar.ssh.ui.viewmodel.MainPagerConfig
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

class MainActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val mainVM = viewModel<MainActivityViewModel>()
            val uiState by mainVM.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by mainVM.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            var sshPendingConnection by remember { mutableStateOf<SshConnectionInfo?>(null) }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides true,
                LocalEnableFloatingBottomBar provides true,
                LocalEnableFloatingBottomBarBlur provides true,
                LocalUiMode provides uiMode,
            ) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                    val mainScreenEntry = @Composable {
                        MainScreen(
                            initialPage = selectedMainPage,
                            onPageChanged = mainVM::setSelectedMainPage,
                            sshPendingConnection = sshPendingConnection,
                            onSshConnectRequest = { conn -> sshPendingConnection = conn },
                            onConsumeSshPending = { sshPendingConnection = null },
                        )
                    }

                    val navDisplay = @Composable {
                        NavDisplay(
                            backStack = navigator.backStack,
                            entryDecorators = listOf(
                                rememberSaveableStateHolderNavEntryDecorator(),
                                rememberViewModelStoreNavEntryDecorator()
                            ),
                            onBack = { navigator.pop() },
                            entryProvider = entryProvider {
                                entry<Route.Main> { mainScreenEntry() }
                                entry<Route.About> { AboutScreen() }
                                entry<Route.Home> { mainScreenEntry() }
                                entry<Route.SuperUser> { mainScreenEntry() }
                                entry<Route.Module> { mainScreenEntry() }
                                entry<Route.Settings> { mainScreenEntry() }
                            }
                        )
                    }

                    Scaffold(containerColor = MiuixTheme.colorScheme.surfaceContainer) { navDisplay() }
                }
            }
        }
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    sshPendingConnection: SshConnectionInfo? = null,
    onSshConnectRequest: (SshConnectionInfo) -> Unit = {},
    onConsumeSshPending: () -> Unit = {},
) {
    val navController = LocalNavigator.current
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(pagerState)
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(true)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    // When SSH pending connection arrives, navigate to page 1
    LaunchedEffect(sshPendingConnection) {
        if (sshPendingConnection != null) {
            mainPagerState.animateToPage(1)
        }
    }

    MainScreenBackHandler(mainPagerState, navController)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier.then(Modifier.layerBackdrop(backdrop)),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) 3 else 0,
                    userScrollEnabled = !mainPagerState.isScrollLocked,
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (isCurrentPage || contentReady) HomeScreen(
                            bottomPadding = bottomInnerPadding,
                            onConnect = onSshConnectRequest,
                        )
                        1 -> if (isCurrentPage || contentReady) SshScreen(
                            bottomPadding = bottomInnerPadding,
                            initialConnection = sshPendingConnection,
                            onConsumed = onConsumeSshPending,
                        )
                        2 -> if (isCurrentPage || contentReady) PlaceholderScreen(PlaceholderPage.Module, bottomInnerPadding)
                        3 -> if (isCurrentPage || contentReady) PlaceholderScreen(PlaceholderPage.Settings, bottomInnerPadding)
                    }
                }
            }
        }

        val bottomBar = @Composable {
            Box(modifier = Modifier.fillMaxWidth()) {
                BottomBar(
                    blurBackdrop = blurBackdrop,
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        Scaffold(bottomBar = bottomBar, containerColor = MiuixTheme.colorScheme.surfaceContainer) { innerPadding ->
            Box(
                modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
            ) {
                pagerContent(innerPadding.calculateBottomPadding())
            }
        }
    }

}


@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}
