package com.material.xray.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.material.xray.ui.configviewer.ConfigViewerRequest
import com.material.xray.ui.configviewer.ConfigViewerScreen
import com.material.xray.ui.home.HomeScreen
import com.material.xray.ui.logs.LogsScreen
import com.material.xray.ui.routing.RoutingScreen
import com.material.xray.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
    val viewModel: MainNavigationViewModel = hiltViewModel()
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val loadedSettings = settings ?: return
    val showTitleBarLogo = loadedSettings.showTitleBarLogo
    val showAdvancedOptions = loadedSettings.showAdvancedOptions
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    var previousRoute by remember { mutableStateOf<String?>(currentRoute) }
    val bottomInset = with(LocalDensity.current) {
        NavigationBarDefaults.windowInsets.getBottom(this).toDp()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(currentRoute) {
        if (previousRoute == Screen.Routing.route && currentRoute != Screen.Routing.route) {
            viewModel.onLeavingRoutingTab()
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(showAdvancedOptions, currentRoute) {
        if (!showAdvancedOptions && currentRoute == Screen.Logs.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // The viewer covers the whole app, navigation bar included, so opening it does not make the
    // bar pop out of existence while the screen is still fading in.
    var configViewerRequest by rememberSaveable(stateSaver = ConfigViewerRequestSaver) {
        mutableStateOf<ConfigViewerRequest?>(null)
    }
    BackHandler(enabled = configViewerRequest != null) { configViewerRequest = null }

    Box {
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
            bottomBar = {
                AnimatedContent(
                    targetState = showAdvancedOptions,
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "advancedNavigationItems",
                ) { showLogs ->
                    val navigationScreens = remember(showLogs) {
                        if (showLogs) {
                            Screen.entries
                        } else {
                            Screen.entries.filterNot { it == Screen.Logs }
                        }
                    }
                    NavigationBar(modifier = Modifier.height(CompactNavigationBarHeight + bottomInset)) {
                        navigationScreens.forEach { screen ->
                            val label = stringResource(screen.labelRes)
                            NavigationBarItem(
                                icon = {
                                    val icon = screen.icon
                                    if (icon != null) {
                                        Icon(icon, contentDescription = label)
                                    } else {
                                        Icon(
                                            painter = painterResource(requireNotNull(screen.iconRes)),
                                            contentDescription = label,
                                        )
                                    }
                                },
                                label = { Text(label) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        showTitleBarLogo = showTitleBarLogo,
                        onOpenServerConfig = { serverId, name ->
                            configViewerRequest = ConfigViewerRequest.Server(serverId, name)
                        },
                        onViewRunningConfig = { configViewerRequest = ConfigViewerRequest.Running },
                    )
                }
                composable(Screen.Logs.route) { LogsScreen(showTitleBarLogo) }
                composable(Screen.Routing.route) { RoutingScreen(showTitleBarLogo) }
                composable(Screen.Settings.route) { SettingsScreen(showTitleBarLogo) }
            }
        }

        AnimatedContent(
            targetState = configViewerRequest,
            transitionSpec = {
                fadeIn(tween(CONFIG_VIEWER_FADE_MS)) togetherWith fadeOut(tween(CONFIG_VIEWER_FADE_MS)) using null
            },
            label = "configViewer",
        ) { request ->
            if (request != null) {
                ConfigViewerScreen(request = request, onBack = { configViewerRequest = null })
            }
        }
    }
}

private val ConfigViewerRequestSaver: Saver<ConfigViewerRequest?, Any> = listSaver(
    save = { request ->
        when (request) {
            null -> emptyList()
            ConfigViewerRequest.Running -> listOf(RUNNING_CONFIG_TAG)
            is ConfigViewerRequest.Server -> listOf(SERVER_CONFIG_TAG, request.serverId, request.name)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            RUNNING_CONFIG_TAG -> ConfigViewerRequest.Running
            SERVER_CONFIG_TAG -> ConfigViewerRequest.Server(saved[1] as Long, saved[2] as String)
            else -> null
        }
    },
)

private const val RUNNING_CONFIG_TAG = "running"
private const val SERVER_CONFIG_TAG = "server"
private const val CONFIG_VIEWER_FADE_MS = 180

private val CompactNavigationBarHeight = 68.dp
