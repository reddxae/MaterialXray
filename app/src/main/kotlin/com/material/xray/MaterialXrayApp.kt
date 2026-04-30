package com.material.xray

import android.app.Application
import com.material.xray.service.SubscriptionUpdateScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MaterialXrayApp : Application() {

    @Inject lateinit var subscriptionUpdateScheduler: SubscriptionUpdateScheduler

    override fun onCreate() {
        super.onCreate()
        subscriptionUpdateScheduler.schedulePeriodicUpdates()
        subscriptionUpdateScheduler.enqueueDueCheckNow()
    }
}
