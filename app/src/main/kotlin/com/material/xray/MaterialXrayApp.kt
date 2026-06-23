package com.material.xray

import android.app.Application
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.data.repository.SettingsRepository
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

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var launcherIconManager: LauncherIconManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            launcherIconManager.apply(settingsRepository.launcherIcon.first())
        }
        subscriptionUpdateScheduler.schedulePeriodicUpdates()
        subscriptionUpdateScheduler.enqueueDueCheckNow()
    }
}
