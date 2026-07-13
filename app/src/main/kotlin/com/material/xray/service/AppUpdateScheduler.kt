package com.material.xray.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun setEnabled(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK_NAME)
        if (enabled) {
            schedule()
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val PERIODIC_WORK_NAME = "app_update_check"
        const val LEGACY_IMMEDIATE_WORK_NAME = "app_update_check_now"
        const val REPEAT_INTERVAL_HOURS = 8L
    }
}
