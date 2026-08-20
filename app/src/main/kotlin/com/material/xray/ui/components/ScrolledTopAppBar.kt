package com.material.xray.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.material.xray.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrolledTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    showLogo: Boolean,
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
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = useDarkIcons
        }
        DisposableEffect(window, view) {
            val previousLightStatusBars = WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars
            onDispose {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = previousLightStatusBars
            }
        }
    }

    TopAppBar(
        title = { AppBarTitle(title, showLogo) },
        expandedHeight = 52.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
        ),
        scrollBehavior = scrollBehavior,
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}

@Composable
fun AppBarTitle(title: String, showLogo: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogo) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_default_monochrome),
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(24.dp),
            )
        }
        Text(title)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
