package com.material.xray.ui.settings

import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Process-wide settings snapshot, loaded before the Settings screen is first composed. */
@Singleton
class SettingsDataState @Inject constructor(
    settingsRepository: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val data: StateFlow<SettingsSnapshot?> = settingsRepository.settingsSnapshot
        .stateIn(scope, SharingStarted.Eagerly, null)
}
