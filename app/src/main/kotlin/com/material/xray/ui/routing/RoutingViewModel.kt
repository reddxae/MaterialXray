package com.material.xray.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.model.RoutingRule
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
) : ViewModel() {
    val rules: StateFlow<List<RoutingRule>> = settingsRepository.routingRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateRule(rule: RoutingRule) {
        viewModelScope.launch {
            settingsRepository.setRoutingRule(rule)
            routingChangeManager.markPendingChanges()
        }
    }

    fun addRule(rule: RoutingRule) {
        viewModelScope.launch {
            settingsRepository.setRoutingRules(rules.value + rule)
            routingChangeManager.markPendingChanges()
        }
    }

    fun deleteRules(ruleIds: Set<String>) {
        if (ruleIds.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setRoutingRules(rules.value.filterNot { it.id in ruleIds })
            routingChangeManager.markPendingChanges()
        }
    }

    fun applyPendingChangesIfNeeded() {
        routingChangeManager.maybeReloadActiveConnection()
    }
}
