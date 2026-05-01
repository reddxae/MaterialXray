package com.material.xray.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainNavigationViewModel @Inject constructor(
    private val routingChangeManager: RoutingChangeManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val showAdvancedOptions: StateFlow<Boolean> = settingsRepository.showAdvancedOptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun onLeavingRoutingTab() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun onAppBackgrounded() {
        routingChangeManager.maybeReloadActiveConnection()
    }
}
