package com.material.xray

import android.app.Application
import android.util.Log
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.core.locale.initializeAppLocales
import com.material.xray.data.repository.BackupManager
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.StartupDiagnosticsLogger
import com.material.xray.service.SubscriptionUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        initializeAppLocales(this)
        appScope.launch {
            runCatching { backupManager.recoverInterruptedRestore() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to recover interrupted backup restore", error) }
            runCatching { startupDiagnosticsLogger.log() }
                .onFailure { error -> Log.e(LOG_TAG, "Unable to record startup diagnostics", error) }
            launcherIconManager.apply(settingsRepository.launcherIcon.first())
            appUpdateScheduler.setEnabled(settingsRepository.appUpdateChecksEnabled.first())
        }
        subscriptionUpdateScheduler.schedulePeriodicUpdates()
        subscriptionUpdateScheduler.enqueueDueCheckNow()
    }

    private companion object {
        const val LOG_TAG = "MaterialXrayApp"
    }
}
