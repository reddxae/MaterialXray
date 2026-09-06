package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.core.xray.GeoDataManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class GeoDataUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            GeoDataUpdateWorkerEntryPoint::class.java,
        )

        return runCatching {
            entryPoint.geoDataManager().refreshIfStale()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeoDataUpdateWorkerEntryPoint {
        fun geoDataManager(): GeoDataManager
    }
}
