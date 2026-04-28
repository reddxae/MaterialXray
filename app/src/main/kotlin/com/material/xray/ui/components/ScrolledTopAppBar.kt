package com.material.xray.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

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

    Box {
        TopAppBar(
            title = { Text(title) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = containerColor,
            ),
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets(0.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(containerColor),
        )
    }
}
