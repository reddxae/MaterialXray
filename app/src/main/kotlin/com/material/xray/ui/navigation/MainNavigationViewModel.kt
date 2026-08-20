package com.material.xray.ui.navigation

import androidx.lifecycle.ViewModel
import com.material.xray.service.RoutingChangeManager
import com.material.xray.ui.settings.SettingsDataState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainNavigationViewModel @Inject constructor(
    private val routingChangeManager: RoutingChangeManager,
    settingsDataState: SettingsDataState,
) : ViewModel() {
    val settings = settingsDataState.data

    fun onLeavingRoutingTab() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun onAppBackgrounded() {
        routingChangeManager.maybeReloadActiveConnection()
    }
}
