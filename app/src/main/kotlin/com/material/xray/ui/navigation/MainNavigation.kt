package com.material.xray.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.ui.home.HomeScreen
import com.material.xray.ui.logs.LogsScreen
import com.material.xray.ui.routing.RoutingScreen
import com.material.xray.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun MainNavigation() {
    val viewModel: MainNavigationViewModel = hiltViewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val navigationScreens = remember(showAdvancedOptions) {
        if (showAdvancedOptions) {
            Screen.entries.toList()
        } else {
            Screen.entries.filterNot { it == Screen.Logs }
        }
    }
    val navigationScreensState = rememberUpdatedState(navigationScreens)
    val pagerState = remember {
        PagerState(pageCount = { navigationScreensState.value.size })
    }
    val coroutineScope = rememberCoroutineScope()
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        snapPositionalThreshold = 0.3f,
    )
    var settledScreen by rememberSaveable { mutableStateOf(Screen.Home) }
    val selectedScreen = navigationScreens[pagerState.currentPage.coerceIn(navigationScreens.indices)]

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

    LaunchedEffect(pagerState, navigationScreens) {
        val retainedScreen = settledScreen.takeIf(navigationScreens::contains) ?: Screen.Home
        val retainedPage = navigationScreens.indexOf(retainedScreen)
        if (pagerState.settledPage != retainedPage) {
            pagerState.scrollToPage(retainedPage)
        }
        if (settledScreen == Screen.Routing && retainedScreen != Screen.Routing) {
            viewModel.onLeavingRoutingTab()
        }
        settledScreen = retainedScreen

        snapshotFlow { pagerState.settledPage }.collect { page ->
            val screen = navigationScreens.getOrNull(page) ?: return@collect
            if (screen != settledScreen) {
                if (settledScreen == Screen.Routing && screen != Screen.Routing) {
                    viewModel.onLeavingRoutingTab()
                }
                settledScreen = screen
            }
        }
    }

    BackHandler(enabled = settledScreen != Screen.Home) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(
                page = navigationScreens.indexOf(Screen.Home),
                animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS),
            )
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
                val visibleScreens = remember(showLogs) {
                    if (showLogs) {
                        Screen.entries.toList()
                    } else {
                        Screen.entries.filterNot { it == Screen.Logs }
                    }
                }
                NavigationBar {
                    visibleScreens.forEach { screen ->
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
                            selected = screen == selectedScreen,
                            onClick = {
                                val page = navigationScreens.indexOf(screen)
                                if (page >= 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(
                                            page = page,
                                            animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            flingBehavior = flingBehavior,
            key = { page -> navigationScreensState.value[page].route },
        ) { page ->
            when (navigationScreensState.value[page]) {
                Screen.Home -> HomeScreen()
                Screen.Routing -> RoutingScreen()
                Screen.Logs -> LogsScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}

private const val NAVIGATION_ANIMATION_DURATION_MS = 250
