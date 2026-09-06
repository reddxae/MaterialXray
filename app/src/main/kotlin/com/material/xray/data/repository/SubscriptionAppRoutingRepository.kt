package com.material.xray.data.repository

import com.material.xray.core.app.AppInventory
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionAppRoutingRepository @Inject constructor(
    private val appBypassDao: AppBypassDao,
    private val subscriptionDao: SubscriptionDao,
    private val appInventory: AppInventory,
) {
    suspend fun apply(routing: SubscriptionAppRouting): Boolean {
        val normalized = routing.normalized() ?: return false
        return replaceActiveRouting(normalized)
    }

    suspend fun applyForSubscription(subscriptionId: Long): Boolean {
        val subscription = subscriptionDao.getById(subscriptionId) ?: return false
        val routing = subscription.toSubscriptionAppRouting() ?: return false
        return replaceActiveRouting(routing)
    }

    private suspend fun replaceActiveRouting(routing: SubscriptionAppRouting?): Boolean {
        val targetAssignments = routing
            ?.let { buildProviderAssignments(it) }
            .orEmpty()
            .sortedWith(compareBy(AppBypassEntity::profileId, AppBypassEntity::packageName))
        if (appBypassDao.getAll() == targetAssignments) return false

        appBypassDao.replaceAll(targetAssignments)
        return true
    }

    private suspend fun buildProviderAssignments(routing: SubscriptionAppRouting): List<AppBypassEntity> {
        if (routing.packageNames.isEmpty()) return emptyList()

        return appInventory.loadRoutingSnapshot().apps
            .mapNotNull { app ->
                val mode = routing.assignmentModeFor(app.packageName) ?: return@mapNotNull null
                mode.toRouteAssignment()
                    .toAppBypassEntity(
                        packageName = app.packageName,
                        profileId = app.profileId,
                        uid = app.uid,
                        manual = false,
                    )
            }
    }

    private fun SubscriptionAppRoutingMode.toRouteAssignment(): AppRouteAssignment = when (this) {
        SubscriptionAppRoutingMode.Direct -> AppRouteAssignment(AppRouteMode.Direct)
        SubscriptionAppRoutingMode.DefaultSelected -> AppRouteAssignment(AppRouteMode.DefaultSelected)
        SubscriptionAppRoutingMode.DefaultOutbound -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
    }
}
