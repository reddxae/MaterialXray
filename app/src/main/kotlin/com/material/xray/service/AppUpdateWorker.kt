package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class AppUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AppUpdateWorkerEntryPoint::class.java,
        )

        return try {
            entryPoint.appUpdateChecker().check()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.success()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppUpdateWorkerEntryPoint {
        fun appUpdateChecker(): AppUpdateChecker
    }
}
