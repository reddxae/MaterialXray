package com.material.xray.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.material.xray.R

enum class Screen(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector? = null,
    @param:DrawableRes val iconRes: Int? = null,
) {
    Home("home", R.string.navigation_home, icon = Icons.Default.Home),
    Routing("routing", R.string.navigation_routing, iconRes = R.drawable.ic_sync_alt_24),
    Logs("logs", R.string.navigation_logs, icon = Icons.AutoMirrored.Filled.Article),
    Settings("settings", R.string.navigation_settings, icon = Icons.Default.Settings),
}
