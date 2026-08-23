package com.material.xray.data.repository

import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.service.ConnectionShutdownManager
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SubscriptionRefreshCoordinator @Inject constructor(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val appBypassDao: AppBypassDao,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val connectionShutdownManager: ConnectionShutdownManager,
    private val serverSelectionCoordinator: ServerSelectionCoordinator,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
) {
    private val operationMutex = Mutex()

    suspend fun refreshAll(): SubscriptionRepository.RefreshBatchResult = operationMutex.withLock {
        refreshSubscriptions(subscriptionRepository.getAllSubscriptions())
    }

    suspend fun refreshDueSubscriptions(
        nowMillis: Long = System.currentTimeMillis(),
    ): SubscriptionRepository.RefreshBatchResult = operationMutex.withLock {
        refreshSubscriptions(subscriptionRepository.getAllSubscriptions().filter { it.isDueForRefresh(nowMillis) })
    }

    suspend fun refreshSubscription(
        subId: Long,
        url: String,
    ): SubscriptionRepository.RefreshResult? = operationMutex.withLock {
        refreshSubscriptionLocked(subId, url)
    }

    suspend fun updateSubscription(
        sub: SubscriptionEntity,
        name: String,
        url: String,
    ): SubscriptionRepository.RefreshResult? = operationMutex.withLock {
        subscriptionRepository.withRefreshLock(sub.id) {
            val updated = subscriptionRepository.updateBeforeRefresh(sub, name, url)
            val prepared = subscriptionRepository.prepareRefresh(updated.id, updated.url) ?: return@withRefreshLock null
            commitRefresh(prepared)
        }
    }

    suspend fun deleteSubscription(subscription: SubscriptionEntity) = operationMutex.withLock {
        serverSelectionCoordinator.withSelectionLock {
            val selectedServer = selectedServerEntity()
            val removedSelectedServerId = selectedServer
                ?.takeIf { it.subscriptionId == subscription.id }
                ?.id
            if (removedSelectedServerId != null) {
                connectionShutdownManager.disconnectIfRunning()
            }

            withContext(NonCancellable) {
                subscriptionRepository.delete(subscription)
                if (removedSelectedServerId != null) {
                    settingsRepository.compareAndSetLastServerId(removedSelectedServerId, -1)
                    activeConfigOverrideStore.clear()
                }
            }
        }
    }

    private suspend fun selectedServerEntity(): ServerEntity? {
        val id = settingsRepository.lastServerId.first()
        if (id < 0) return null
        return serverRepository.getById(id)
    }

    private suspend fun refreshSubscriptions(
        subscriptions: List<SubscriptionEntity>,
    ): SubscriptionRepository.RefreshBatchResult {
        val successes = mutableMapOf<Long, SubscriptionRepository.RefreshResult>()
        val failures = mutableMapOf<Long, IOException>()
        subscriptions.forEach { subscription ->
            try {
                refreshSubscriptionLocked(subscription.id, subscription.url)?.let { result ->
                    successes[subscription.id] = result
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                failures[subscription.id] = error
            }
        }
        return SubscriptionRepository.RefreshBatchResult(successes = successes, failures = failures)
    }

    private suspend fun refreshSubscriptionLocked(
        subscriptionId: Long,
        url: String,
    ): SubscriptionRepository.RefreshResult? = subscriptionRepository.withRefreshLock(subscriptionId) {
        val prepared = subscriptionRepository.prepareRefresh(subscriptionId, url) ?: return@withRefreshLock null
        commitRefresh(prepared)
    }

    private suspend fun commitRefresh(
        prepared: SubscriptionRepository.PreparedRefresh,
    ): SubscriptionRepository.RefreshResult? = serverSelectionCoordinator.withSelectionLock {
        val selectedBeforeRefresh = selectedServerEntity()
        withContext(NonCancellable) {
            val result = subscriptionRepository.commitRefresh(prepared) ?: return@withContext null
            syncSelectedServerAfterRefresh(selectedBeforeRefresh, result)
            syncAppRoutesAfterRefresh(result)
            if (selectedBeforeRefresh?.subscriptionId == result.subscriptionId) {
                providerRoutingCoordinator.refreshSelectedServer()
            }
            result
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
    }

    private suspend fun syncSelectedServerAfterRefresh(
        selectedBeforeRefresh: ServerEntity?,
        refreshResult: SubscriptionRepository.RefreshResult,
    ) {
        val selectedServer = selectedBeforeRefresh ?: return
        if (settingsRepository.lastServerId.first() != selectedServer.id) return

        when (val outcome = selectedServerRefreshOutcome(selectedServer, refreshResult.subscriptionId, refreshResult)) {
            SelectedServerRefreshOutcome.Unchanged -> Unit
            SelectedServerRefreshOutcome.Removed -> {
                try {
                    connectionShutdownManager.disconnectIfRunning()
                } finally {
                    settingsRepository.compareAndSetLastServerId(selectedServer.id, -1)
                    activeConfigOverrideStore.clear()
                }
            }
            is SelectedServerRefreshOutcome.Replaced -> {
                settingsRepository.compareAndSetLastServerId(selectedServer.id, outcome.serverId)
                activeConfigOverrideStore.clear()
            }
        }
    }
}

internal sealed interface SelectedServerRefreshOutcome {
    data object Unchanged : SelectedServerRefreshOutcome
    data object Removed : SelectedServerRefreshOutcome
    data class Replaced(val serverId: Long) : SelectedServerRefreshOutcome
}

internal fun selectedServerRefreshOutcome(
    selectedServer: ServerEntity?,
    refreshedSubscriptionId: Long,
    refreshResult: SubscriptionRepository.RefreshResult?,
): SelectedServerRefreshOutcome {
    if (selectedServer?.subscriptionId != refreshedSubscriptionId) {
        return SelectedServerRefreshOutcome.Unchanged
    }
    val replacementId = refreshResult?.serverIdReplacements?.get(selectedServer.id)
        ?: return SelectedServerRefreshOutcome.Removed
    return if (replacementId == selectedServer.id) {
        SelectedServerRefreshOutcome.Unchanged
    } else {
        SelectedServerRefreshOutcome.Replaced(replacementId)
    }
}
