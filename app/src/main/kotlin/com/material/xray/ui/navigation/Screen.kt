package com.material.xray.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.material.xray.R

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
    @param:DrawableRes val iconRes: Int? = null,
) {
    Home("home", "Home", icon = Icons.Default.Home),
    Routing("routing", "Routing", iconRes = R.drawable.ic_sync_alt_24),
    Logs("logs", "Logs", icon = Icons.AutoMirrored.Filled.Article),
    Settings("settings", "Settings", icon = Icons.Default.Settings),
}
