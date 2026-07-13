package com.material.xray.data.repository

import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.ConnectionState
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SubscriptionRefreshCoordinator @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val appBypassDao: AppBypassDao,
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val subscriptionRoutingRepository: SubscriptionRoutingRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
) {
    suspend fun refreshAll(): SubscriptionRepository.RefreshBatchResult {
        val selectedBeforeRefresh = selectedServerEntity()
        val result = subscriptionRepository.refreshAll()
        syncAppRoutesAfterRefreshResults(result.successes)
        syncSelectedServerAfterRefreshResults(selectedBeforeRefresh, result.successes)
        return result
    }

    suspend fun refreshDueSubscriptions(
        nowMillis: Long = System.currentTimeMillis(),
    ): SubscriptionRepository.RefreshBatchResult {
        val selectedBeforeRefresh = selectedServerEntity()
        val result = subscriptionRepository.refreshDueSubscriptions(nowMillis)
        syncAppRoutesAfterRefreshResults(result.successes)
        syncSelectedServerAfterRefreshResults(selectedBeforeRefresh, result.successes)
        return result
    }

    suspend fun refreshSubscription(
        subId: Long,
        url: String,
    ): SubscriptionRepository.RefreshResult? {
        val selectedBeforeRefresh = selectedServerEntity()
        val result = subscriptionRepository.refresh(subId, url)
        syncAppRoutesAfterRefresh(result)
        syncSelectedServerAfterRefresh(selectedBeforeRefresh, subId, result)
        return result
    }

    suspend fun updateSubscription(
        sub: SubscriptionEntity,
        name: String,
        url: String,
    ): SubscriptionRepository.RefreshResult? {
        val selectedBeforeRefresh = selectedServerEntity()
        val result = subscriptionRepository.update(sub, name, url)
        syncAppRoutesAfterRefresh(result)
        syncSelectedServerAfterRefresh(selectedBeforeRefresh, sub.id, result)
        return result
    }

    private suspend fun selectedServerEntity(): ServerEntity? {
        val id = settingsRepository.lastServerId.first()
        if (id < 0) return null
        return serverRepository.getById(id)
    }

    private suspend fun syncSelectedServerAfterRefreshResults(
        selectedBeforeRefresh: ServerEntity?,
        refreshResults: Map<Long, SubscriptionRepository.RefreshResult>,
    ) {
        selectedBeforeRefresh?.let { previousServer ->
            refreshResults[previousServer.subscriptionId]?.let { refreshResult ->
                syncSelectedServerAfterRefresh(
                    selectedBeforeRefresh = previousServer,
                    refreshedSubscriptionId = previousServer.subscriptionId,
                    refreshResult = refreshResult,
                )
            }
        }
    }

    private suspend fun syncAppRoutesAfterRefreshResults(
        refreshResults: Map<Long, SubscriptionRepository.RefreshResult>,
    ) {
        refreshResults.values.forEach { refreshResult ->
            syncAppRoutesAfterRefresh(refreshResult)
        }
    }

    private suspend fun syncAppRoutesAfterRefresh(
        refreshResult: SubscriptionRepository.RefreshResult?,
    ) {
        refreshResult?.serverIdReplacements.orEmpty().forEach { (oldServerId, newServerId) ->
            if (oldServerId != newServerId) {
                appBypassDao.updateServerId(oldServerId, newServerId)
            }
        }

        val result = refreshResult ?: return
        if (settingsRepository.routingPolicyControl.first() != RoutingPolicyControl.SubscriptionProvider) return

        val selectedServerId = settingsRepository.lastServerId.first()
        val selectedSubscriptionId = if (selectedServerId in result.serverIdReplacements) {
            result.subscriptionId
        } else {
            selectedServerEntity()?.subscriptionId
        }
        if (selectedSubscriptionId != result.subscriptionId) return

        val appRoutingChanged = subscriptionAppRoutingRepository.applyForSubscription(result.subscriptionId)
        val routingChanged = subscriptionRoutingRepository.applyForSubscription(result.subscriptionId)
        if (appRoutingChanged || routingChanged) {
            if (connectionStateCoordinator.state.value is ConnectionState.Connected) {
                routingChangeManager.markPendingChanges(
                    if (routingChanged) PendingRoutingChange.XRAY_CONFIG else PendingRoutingChange.APP_ROUTING,
                )
            }
        }
    }

    private suspend fun syncSelectedServerAfterRefresh(
        selectedBeforeRefresh: ServerEntity?,
        refreshedSubscriptionId: Long,
        refreshResult: SubscriptionRepository.RefreshResult?,
    ) {
        if (selectedBeforeRefresh?.subscriptionId != refreshedSubscriptionId) return

        val replacementId = refreshResult?.serverIdReplacements?.get(selectedBeforeRefresh.id) ?: -1L

        if (replacementId != selectedBeforeRefresh.id) {
            settingsRepository.setLastServerId(replacementId)
        }
    }
}
