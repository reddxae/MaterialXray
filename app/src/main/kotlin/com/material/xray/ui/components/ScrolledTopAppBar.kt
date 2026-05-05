package com.material.xray.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrolledTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val surface = MaterialTheme.colorScheme.surface
    val scrolledSurface = MaterialTheme.colorScheme.surfaceContainer
    val overlappedFraction by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.overlappedFraction.coerceIn(0f, 1f) }
    }
    val containerColor = lerp(surface, scrolledSurface, overlappedFraction)
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }

    if (window != null && !view.isInEditMode) {
        val useDarkIcons = containerColor.luminance() > 0.5f
        SideEffect {
            window.statusBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkIcons
        }
        DisposableEffect(window, view) {
            val previousStatusBarColor = window.statusBarColor
            val previousLightStatusBars = WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars
            onDispose {
                window.statusBarColor = previousStatusBarColor
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = previousLightStatusBars
            }
        }
    }

    TopAppBar(
        title = { Text(title) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
        ),
        scrollBehavior = scrollBehavior,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
