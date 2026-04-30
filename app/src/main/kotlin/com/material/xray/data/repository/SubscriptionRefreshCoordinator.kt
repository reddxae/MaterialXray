package com.material.xray.data.repository

import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRefreshCoordinator @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun refreshAll(): Map<Long, SubscriptionRepository.RefreshResult> {
        val selectedBeforeRefresh = selectedServerEntity()
        val results = subscriptionRepository.refreshAll()
        syncSelectedServerAfterRefreshResults(selectedBeforeRefresh, results)
        return results
    }

    suspend fun refreshDueSubscriptions(
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<Long, SubscriptionRepository.RefreshResult> {
        val selectedBeforeRefresh = selectedServerEntity()
        val results = subscriptionRepository.refreshDueSubscriptions(nowMillis)
        syncSelectedServerAfterRefreshResults(selectedBeforeRefresh, results)
        return results
    }

    suspend fun refreshSubscription(
        subId: Long,
        url: String,
    ): SubscriptionRepository.RefreshResult? {
        val selectedBeforeRefresh = selectedServerEntity()
        val result = subscriptionRepository.refresh(subId, url)
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

    private suspend fun syncSelectedServerAfterRefresh(
        selectedBeforeRefresh: ServerEntity?,
        refreshedSubscriptionId: Long,
        refreshResult: SubscriptionRepository.RefreshResult?,
    ) {
        if (selectedBeforeRefresh?.subscriptionId != refreshedSubscriptionId) return

        val replacementId = refreshResult
            ?.serverIdByConfigJson
            ?.get(selectedBeforeRefresh.configJson)
            ?: -1L

        if (replacementId != selectedBeforeRefresh.id) {
            settingsRepository.setLastServerId(replacementId)
        }
    }
}
