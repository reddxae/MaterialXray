package com.material.xray.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fades a scrolling list into the background at both ends, so content that is cut off looks cut off
 * rather than clipped.
 *
 * Overlays the list, so it belongs in the same [Box] and after the list in the composition order.
 */
@Composable
fun BoxScope.ScrollFadeEdges(fadeColor: Color = MaterialTheme.colorScheme.surface) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(8.dp)
            .background(Brush.verticalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f)))),
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(12.dp)
            .background(Brush.verticalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor))),
    )
}
