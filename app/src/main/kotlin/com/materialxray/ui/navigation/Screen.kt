package com.materialxray.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Routing("routing", "Routing", Icons.Default.Apps),
    Logs("logs", "Logs", Icons.AutoMirrored.Filled.Article),
    Settings("settings", "Settings", Icons.Default.Settings),
}
