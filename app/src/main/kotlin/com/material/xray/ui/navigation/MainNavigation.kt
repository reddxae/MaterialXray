package com.material.xray.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.material.xray.ui.home.HomeScreen
import com.material.xray.ui.logs.LogsScreen
import com.material.xray.ui.routing.RoutingScreen
import com.material.xray.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
    val viewModel: MainNavigationViewModel = hiltViewModel()
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    var previousRoute by remember { mutableStateOf<String?>(currentRoute) }

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
                NavigationBar {
                    navigationScreens.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
                                label = { Text(screen.label) },
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
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Logs.route) { LogsScreen() }
            composable(Screen.Routing.route) { RoutingScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
