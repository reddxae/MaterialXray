package com.materialxray

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.materialxray.ui.navigation.MainNavigation
import com.materialxray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionPrefs().edit()
            .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionOnce()
        setContent {
            MaterialXrayTheme {
                MainNavigation()
            }
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val prefs = notificationPermissionPrefs()
        if (prefs.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)) return

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationPermissionPrefs() =
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
        const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
    }
}
