package com.material.xray.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.material.xray.data.repository.SubscriptionRefreshCoordinator
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

class SubscriptionUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SubscriptionUpdateWorkerEntryPoint::class.java,
        )

        return runCatching {
            entryPoint.subscriptionRefreshCoordinator().refreshDueSubscriptions()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SubscriptionUpdateWorkerEntryPoint {
        fun subscriptionRefreshCoordinator(): SubscriptionRefreshCoordinator
    }
}
