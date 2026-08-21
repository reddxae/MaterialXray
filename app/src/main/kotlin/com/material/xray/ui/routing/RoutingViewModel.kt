package com.material.xray.ui.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.repository.ProviderRoutingAvailability
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.selectedProviderRoutingAvailability
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.SubscriptionRouting
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RoutingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val serverRepository: ServerRepository,
    private val subscriptionDao: SubscriptionDao,
) : ViewModel() {
    val rules: StateFlow<List<RoutingRule>> = settingsRepository.routingRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val selectedProviderRouting: StateFlow<ProviderRoutingAvailability?> = combine(
        settingsRepository.lastServerId,
        serverRepository.observeAll(),
        subscriptionDao.observeAll(),
        ::selectedProviderRoutingAvailability,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )
    val routingPolicyControl: StateFlow<RoutingPolicyControl> = combine(
        settingsRepository.routingPolicyControl,
        selectedProviderRouting,
    ) { policy, provider ->
        if (policy == RoutingPolicyControl.SubscriptionProvider && provider?.xrayRoutingProvided == true) {
            RoutingPolicyControl.SubscriptionProvider
        } else {
            RoutingPolicyControl.User
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.User)
    val automaticRoutingProviderName: StateFlow<String?> = selectedProviderRouting
        .map { it?.providerName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    fun setAllRulesEnabled(enabled: Boolean) {
        val updatedRules = rules.value.map { it.copy(enabled = enabled) }
        if (updatedRules == rules.value) return
        viewModelScope.launch {
            settingsRepository.setRoutingRules(updatedRules)
            routingChangeManager.markPendingChanges()
        }
    }

    fun resetRulesToDefaults() {
        viewModelScope.launch {
            settingsRepository.setSubscriptionRouting(
                SubscriptionRouting(RoutingRuleCatalog.defaults()),
            )
            routingChangeManager.markPendingChanges()
        }
    }

    fun applyPendingChangesIfNeeded() {
        routingChangeManager.maybeReloadActiveConnection()
    }

    fun switchToManualRouting() {
        viewModelScope.launch {
            settingsRepository.setRoutingPolicyControl(RoutingPolicyControl.User)
        }
    }
}
