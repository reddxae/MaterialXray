package com.material.xray.data.repository

import com.material.xray.core.app.AppInventory
import com.material.xray.core.app.appKey
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SubscriptionAppRoutingRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val appBypassDao: AppBypassDao,
    private val subscriptionDao: SubscriptionDao,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventory,
) {
    suspend fun apply(routing: SubscriptionAppRouting): Boolean {
        val normalized = routing.normalized() ?: return false
        return replaceActiveRouting(normalized)
    }

    suspend fun applyForSubscription(subscriptionId: Long): Boolean {
        val subscription = subscriptionDao.getById(subscriptionId) ?: return false
        return replaceActiveRouting(subscription.toSubscriptionAppRouting())
    }

    suspend fun applyForSelectedServerIfProviderControlled(): Boolean {
        if (settingsRepository.routingPolicyControl.first() != RoutingPolicyControl.SubscriptionProvider) return false
        val serverId = settingsRepository.lastServerId.first()
        val server = serverRepository.getById(serverId) ?: return false
        return applyForSubscription(server.subscriptionId)
    }

    suspend fun syncInstalledApps(): Boolean {
        if (settingsRepository.routingPolicyControl.first() != RoutingPolicyControl.SubscriptionProvider) return false
        val serverId = settingsRepository.lastServerId.first()
        val server = serverRepository.getById(serverId) ?: return false
        return applyForSubscription(server.subscriptionId)
    }

    private suspend fun replaceActiveRouting(routing: SubscriptionAppRouting?): Boolean {
        val hadExistingAssignments = appBypassDao.getAll().isNotEmpty()
        appBypassDao.deleteAll()
        val applied = if (routing != null) syncInstalledApps(routing) else false
        return applied || hadExistingAssignments
    }

    private suspend fun syncInstalledApps(routing: SubscriptionAppRouting): Boolean {
        val packages = routing.packageNames.toSet()
        if (packages.isEmpty()) return false

        val assignment = routing.mode.toRouteAssignment()
        val existingByKey = appBypassDao.getAll().associateBy { appKey(it.profileId, it.packageName) }
        var changed = false

        appInventory.loadRoutingSnapshot().apps
            .filter { it.packageName in packages }
            .forEach { app ->
                val existing = existingByKey[app.appKey]
                if (existing.hasSameProviderAssignment(assignment, app.uid)) return@forEach
                appBypassDao.upsert(
                    assignment.toAppBypassEntity(
                        packageName = app.packageName,
                        profileId = app.profileId,
                        uid = app.uid,
                        manual = false,
                    ),
                )
                changed = true
            }

        return changed
    }

    private fun AppBypassEntity?.hasSameProviderAssignment(
        assignment: AppRouteAssignment,
        uid: Int,
    ): Boolean = this != null &&
        !manual &&
        this.uid == uid &&
        routeAssignment() == assignment

    private fun SubscriptionAppRoutingMode.toRouteAssignment(): AppRouteAssignment = when (this) {
        SubscriptionAppRoutingMode.Direct -> AppRouteAssignment(AppRouteMode.Direct)
        SubscriptionAppRoutingMode.DefaultSelected -> AppRouteAssignment(AppRouteMode.DefaultSelected)
        SubscriptionAppRoutingMode.DefaultOutbound -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
    }
}
