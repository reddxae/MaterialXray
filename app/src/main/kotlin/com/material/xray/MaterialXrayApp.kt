package com.material.xray

import android.app.Application
import android.util.Log
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.locale.initializeAppLocales
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.di.ApplicationScope
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.OemAutostartManager
import com.material.xray.service.StartupDiagnosticsLogger
import com.material.xray.service.SubscriptionUpdateScheduler
import com.material.xray.ui.home.HomeDataState
import com.material.xray.ui.settings.SettingsDataState
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class MaterialXrayApp : Application() {

    @Inject lateinit var subscriptionUpdateScheduler: SubscriptionUpdateScheduler

    @Inject lateinit var appUpdateScheduler: AppUpdateScheduler

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var launcherIconManager: LauncherIconManager

    @Inject lateinit var backupManager: BackupManager

    @Inject lateinit var startupDiagnosticsLogger: StartupDiagnosticsLogger

    @Inject lateinit var oemAutostartManager: OemAutostartManager

    /**
     * Injected for its construction side effect: building the holder eagerly starts loading the
     * home screen data (Room and DataStore) during application startup, before the first
     * composition subscribes, so [MainActivity] can dismiss its splash screen with a fully
     * populated first frame.
     */
    @Inject lateinit var homeDataState: HomeDataState

    /** Eagerly starts the atomic Settings snapshot before that tab is first opened. */
    @Inject lateinit var settingsDataState: SettingsDataState

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        // Per-app locales must be applied before super.onCreate(): Hilt injects this class there,
        // which constructs HomeDataState, and that eagerly builds locale-dependent server
        // summaries. Initializing afterwards would race that first snapshot on API <= 32.
        initializeAppLocales(this)
        super.onCreate()
        appScope.launch {
            if (settingsRepository.autoConnect.first()) {
                delay(STARTUP_BACKGROUND_WORK_DELAY_SECONDS * 1_000)
                oemAutostartManager.restoreRootGrant()
            }
        }
        appScope.launch {
            runCatching { backupManager.recoverInterruptedRestore() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to recover interrupted backup restore", error) }
            runCatching { startupDiagnosticsLogger.logIfMissing() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to record startup diagnostics", error) }
            launcherIconManager.apply(settingsRepository.launcherIcon.first())
            appUpdateScheduler.setEnabled(settingsRepository.appUpdateChecksEnabled.first())
        }
        subscriptionUpdateScheduler.schedulePeriodicUpdates()
        subscriptionUpdateScheduler.enqueueDueCheckNow(STARTUP_BACKGROUND_WORK_DELAY_SECONDS)
    }

    private companion object {
        const val LOG_TAG = "MaterialXrayApp"
        const val STARTUP_BACKGROUND_WORK_DELAY_SECONDS = 30L
    }
}
