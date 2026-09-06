package com.material.xray.ui.settings

import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Process-wide settings snapshot, loaded before the Settings screen is first composed. */
@Singleton
class SettingsDataState @Inject constructor(
    settingsRepository: SettingsRepository,
    subscriptionRepository: SubscriptionRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val data: StateFlow<SettingsSnapshot?> = settingsRepository.settingsSnapshot
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Whether the subscription behind the currently selected server demands the hardware ID.
     * While it does and sending is enabled, the toggle is locked so the provider policy holds.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedSubscriptionRequiresHardwareId: StateFlow<Boolean> = settingsRepository.lastServerId
        .flatMapLatest { serverId ->
            subscriptionRepository.observeRequiresHardwareIdForServer(serverId)
        }
        .map { it == true }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MILLIS), false)

    private companion object {
        const val STOP_GRACE_MILLIS = 5_000L
    }
}
