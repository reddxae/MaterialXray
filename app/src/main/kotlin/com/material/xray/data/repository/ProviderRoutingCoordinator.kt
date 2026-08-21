package com.material.xray.data.repository

import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProviderRoutingActiveUpdate {
    APPLY_IF_CONNECTED,
    DEFER,
}

sealed interface ProviderRoutingRefreshResult {
    data object NotProviderControlled : ProviderRoutingRefreshResult
    data object NoSelectedServer : ProviderRoutingRefreshResult
    data object Unchanged : ProviderRoutingRefreshResult
    data class Persisted(val change: PendingRoutingChange) : ProviderRoutingRefreshResult
    data class ActiveUpdateRequested(val change: PendingRoutingChange) : ProviderRoutingRefreshResult
}

internal sealed interface ProviderRoutingSelection {
    data object NotProviderControlled : ProviderRoutingSelection
    data object NoSelectedServer : ProviderRoutingSelection
    data class Selected(
        val subscriptionId: Long,
        val appRoutingProvided: Boolean = true,
        val xrayRoutingProvided: Boolean = true,
    ) : ProviderRoutingSelection
}

@Singleton
class ProviderRoutingCoordinator internal constructor(
    private val loadSelection: suspend () -> ProviderRoutingSelection,
    private val applyAppRouting: suspend (Long) -> Boolean,
    private val applyXrayRouting: suspend (Long) -> Boolean,
    private val applyActiveConnectionChange: (PendingRoutingChange) -> Boolean,
) {
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        serverRepository: ServerRepository,
        subscriptionDao: SubscriptionDao,
        subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
        subscriptionRoutingRepository: SubscriptionRoutingRepository,
        routingChangeManager: RoutingChangeManager,
    ) : this(
        loadSelection = {
            if (settingsRepository.routingPolicyControl.first() != RoutingPolicyControl.SubscriptionProvider) {
                ProviderRoutingSelection.NotProviderControlled
            } else {
                val serverId = settingsRepository.lastServerId.first()
                val server = serverRepository.getById(serverId)
                if (server == null) {
                    ProviderRoutingSelection.NoSelectedServer
                } else {
                    val availability = subscriptionDao.getById(server.subscriptionId)
                        ?.providerRoutingAvailability()
                    ProviderRoutingSelection.Selected(
                        subscriptionId = server.subscriptionId,
                        appRoutingProvided = availability?.appRoutingProvided == true,
                        xrayRoutingProvided = availability?.xrayRoutingProvided == true,
                    )
                }
            }
        },
        applyAppRouting = subscriptionAppRoutingRepository::applyForSubscription,
        applyXrayRouting = subscriptionRoutingRepository::applyForSubscription,
        applyActiveConnectionChange = routingChangeManager::requestActiveConnectionUpdate,
    )

    private val refreshMutex = Mutex()

    suspend fun refreshSelectedServer(
        activeUpdate: ProviderRoutingActiveUpdate = ProviderRoutingActiveUpdate.APPLY_IF_CONNECTED,
    ): ProviderRoutingRefreshResult = refreshMutex.withLock {
        val selection = loadSelection()
        when (selection) {
            ProviderRoutingSelection.NotProviderControlled -> ProviderRoutingRefreshResult.NotProviderControlled
            ProviderRoutingSelection.NoSelectedServer -> ProviderRoutingRefreshResult.NoSelectedServer
            is ProviderRoutingSelection.Selected -> refreshSelectedSubscription(selection, activeUpdate)
        }
    }

    private suspend fun refreshSelectedSubscription(
        selection: ProviderRoutingSelection.Selected,
        activeUpdate: ProviderRoutingActiveUpdate,
    ): ProviderRoutingRefreshResult {
        val appRoutingChanged = selection.appRoutingProvided && applyAppRouting(selection.subscriptionId)
        val xrayRoutingChanged = selection.xrayRoutingProvided && applyXrayRouting(selection.subscriptionId)
        if (!appRoutingChanged && !xrayRoutingChanged) return ProviderRoutingRefreshResult.Unchanged

        val change = if (xrayRoutingChanged) {
            PendingRoutingChange.XRAY_CONFIG
        } else {
            PendingRoutingChange.APP_ROUTING
        }
        val activeUpdateRequested = activeUpdate == ProviderRoutingActiveUpdate.APPLY_IF_CONNECTED &&
            applyActiveConnectionChange(change)
        return if (activeUpdateRequested) {
            ProviderRoutingRefreshResult.ActiveUpdateRequested(change)
        } else {
            ProviderRoutingRefreshResult.Persisted(change)
        }
    }
}
