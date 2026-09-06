package com.material.xray.ui.settings

import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Process-wide settings snapshot, loaded before the Settings screen is first composed. */
@Singleton
class SettingsDataState @Inject constructor(
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    subscriptionRepository: SubscriptionRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val data: StateFlow<SettingsSnapshot?> = settingsRepository.settingsSnapshot
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Whether the subscription behind the currently selected server demands the hardware ID.
     * While it does and sending is enabled, the toggle is locked so the provider policy holds.
     */
    val selectedSubscriptionRequiresHardwareId: StateFlow<Boolean> = combine(
        serverRepository.observeAll(),
        subscriptionRepository.observeAll(),
        settingsRepository.lastServerId,
    ) { servers, subscriptions, selectedServerId ->
        val subscriptionId = servers.find { it.id == selectedServerId }?.subscriptionId
        subscriptions.find { it.id == subscriptionId }?.requiresHardwareId ?: false
    }.stateIn(scope, SharingStarted.Eagerly, false)
}
