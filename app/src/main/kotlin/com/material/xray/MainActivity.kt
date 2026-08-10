package com.material.xray

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.material.xray.core.locale.notifyAppLocaleChanged
import com.material.xray.ui.home.HomeDataState
import com.material.xray.ui.navigation.MainNavigation
import com.material.xray.ui.theme.MaterialXrayTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var homeDataState: HomeDataState

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        notifyAppLocaleChanged()
        enableEdgeToEdge()
        // Hold the splash screen until the home data snapshot is ready, so the first visible
        // frame renders the subscription list, server rows, and selected server together instead
        // of popping in piece by piece. On warm starts the snapshot is already loaded and the
        // splash screen dismisses on the first frame.
        val splashShownAtMillis = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            keepSplashOnScreen(
                homeDataLoaded = homeDataState.data.value != null,
                elapsedMillis = SystemClock.uptimeMillis() - splashShownAtMillis,
            )
        }
        setContent {
            MaterialXrayTheme {
                MainNavigation()
            }
        }
    }
}

/**
 * The splash screen stays up only while the home data snapshot is still loading, and never longer
 * than [SPLASH_SCREEN_TIMEOUT_MS], so a slow or failed load cannot hold it up indefinitely.
 */
internal fun keepSplashOnScreen(homeDataLoaded: Boolean, elapsedMillis: Long): Boolean = !homeDataLoaded && elapsedMillis < SPLASH_SCREEN_TIMEOUT_MS

internal const val SPLASH_SCREEN_TIMEOUT_MS = 2_000L
