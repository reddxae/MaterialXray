package com.material.xray.ui.apps

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.app.AppInventory
import com.material.xray.core.app.appKey
import com.material.xray.core.locale.localizedString
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.data.repository.ProviderRoutingCoordinator
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.endpointSummary
import com.material.xray.model.proxyOutboundCount
import com.material.xray.service.AlwaysOnVpnState
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppItem(
    val appKey: String,
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val systemApp: Boolean,
    val profileId: Int,
    val workProfile: Boolean,
    val routeKey: String,
    val routeKind: AppRouteKind,
    val customRouted: Boolean,
    val routeTitle: AppRouteText,
    val routeDescription: AppRouteText,
)

sealed interface AppRouteText {
    data class Resource(
        @param:StringRes val resourceId: Int,
        val arguments: List<Any> = emptyList(),
    ) : AppRouteText

    data class PluralResource(
        @param:PluralsRes val resourceId: Int,
        val quantity: Int,
        val arguments: List<Any> = emptyList(),
    ) : AppRouteText

    data class Raw(val value: String) : AppRouteText
}

enum class AppRouteKind {
    INHERIT,
    DEFAULT,
    DIRECT,
    BYPASS,
    SERVER,
}

data class AppRouteOption(
    val key: String,
    val title: AppRouteText,
    val description: AppRouteText,
    val kind: AppRouteKind,
    val serverId: Long? = null,
)

private data class AppListFilters(
    val searchQuery: String,
    val showSystemApps: Boolean,
    val showWorkProfileApps: Boolean,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appBypassDao: AppBypassDao,
    private val subscriptionDao: SubscriptionDao,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    alwaysOnVpnState: AlwaysOnVpnState,
    private val providerRoutingCoordinator: ProviderRoutingCoordinator,
    private val routingChangeManager: RoutingChangeManager,
    private val appInventory: AppInventory,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    private val _showWorkProfileApps = MutableStateFlow(true)
    val showWorkProfileApps: StateFlow<Boolean> = _showWorkProfileApps

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps
    private var loadAppsJob: Job? = null
    private var loadAppsRunId = 0L
    private var routingRefreshJob: Job? = null

    private val effectiveUseRootService = combine(
        settingsRepository.useRootService,
        alwaysOnVpnState.active,
    ) { useRootService, alwaysOnVpn -> useRootService && !alwaysOnVpn }

    val appSpecificServerNoteShown: StateFlow<Boolean> = settingsRepository.appSpecificServerNoteShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val routingPolicyControl: StateFlow<RoutingPolicyControl> = settingsRepository.routingPolicyControl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutingPolicyControl.default)

    val automaticRoutingProviderName: StateFlow<String?> = combine(
        settingsRepository.lastServerId,
        serverRepository.observeAll(),
        subscriptionDao.observeAll(),
    ) { selectedServerId, servers, subscriptions ->
        val subscriptionId = servers.firstOrNull { it.id == selectedServerId }?.subscriptionId
        subscriptions.firstOrNull { it.id == subscriptionId }?.name?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null,
    )

    private val bypassedApps = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val installedApps = MutableStateFlow<List<AppItem>>(emptyList())
    private val _hasWorkProfileApps = MutableStateFlow(false)
    val hasWorkProfileApps: StateFlow<Boolean> = _hasWorkProfileApps

    val routeOptions: StateFlow<List<AppRouteOption>> = combine(
        serverRepository.observeAll(),
        settingsRepository.showAdvancedOptions,
        effectiveUseRootService,
    ) { servers, showAdvancedOptions, useRootService ->
        if (!useRootService) {
            return@combine listOf(DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION)
        }
        buildList {
            if (showAdvancedOptions) add(INHERIT_ROUTE_OPTION)
            add(DEFAULT_ROUTE_OPTION)
            add(DIRECT_ROUTE_OPTION)
            if (showAdvancedOptions) add(BYPASS_ROUTE_OPTION)
            servers.forEach { server -> add(server.toRouteOption()) }
        }
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION),
        )

    private val appListFilters = combine(
        _searchQuery,
        _showSystemApps,
        _showWorkProfileApps,
    ) { searchQuery, showSystemApps, showWorkProfileApps ->
        AppListFilters(
            searchQuery = searchQuery,
            showSystemApps = showSystemApps,
            showWorkProfileApps = showWorkProfileApps,
        )
    }

    val apps: StateFlow<List<AppItem>> = combine(
        installedApps,
        bypassedApps,
        appListFilters,
        routeOptions,
        effectiveUseRootService,
    ) { installed, assignments, filters, options, useRootService ->
        val assignmentByApp = assignments.associateBy { appKey(it.profileId, it.packageName) }
        val serverOptionsById = options
            .filter { it.kind == AppRouteKind.SERVER && it.serverId != null }
            .associateBy { requireNotNull(it.serverId) }
        installed
            .map { app ->
                val assignment = assignmentByApp[app.appKey]
                val option = app.resolveRouteOption(assignment, serverOptionsById, useRootService)
                app.copy(
                    routeKey = option.key,
                    routeKind = option.kind,
                    customRouted = option.kind != AppRouteKind.DEFAULT,
                    routeTitle = option.title,
                    routeDescription = option.description,
                )
            }
            .filter { filters.showSystemApps || !it.systemApp }
            .filter { filters.showWorkProfileApps || !it.workProfile }
            .filter {
                filters.searchQuery.isEmpty() ||
                    it.name.contains(filters.searchQuery, ignoreCase = true) ||
                    it.packageName.contains(filters.searchQuery, ignoreCase = true) ||
                    context.localizedString(
                        if (it.workProfile) R.string.apps_work_profile_label else R.string.apps_personal_profile_label,
                    ).contains(filters.searchQuery, ignoreCase = true)
            }
            .sortedWith(
                compareBy<AppItem> { !it.customRouted }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.profileId }
                    .thenBy { it.packageName },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshApps() {
        refreshProviderRouting()
        loadApps()
    }

    fun onVisible() {
        refreshProviderRouting()
        if (installedApps.value.isEmpty() && loadAppsJob?.isActive != true) loadApps()
    }

    fun onHidden() {
        loadAppsRunId++
        val wasLoading = loadAppsJob != null
        loadAppsJob?.cancel()
        loadAppsJob = null
        if (wasLoading) _isLoadingApps.value = false
    }

    private fun loadApps() {
        loadAppsJob?.cancel()
        val runId = ++loadAppsRunId
        loadAppsJob = viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                val snapshot = appInventory.loadSnapshot()
                _hasWorkProfileApps.value = snapshot.profileIds.size > 1
                val apps = snapshot.apps
                    .filterNot { it.packageName == context.packageName }
                    .map { app ->
                        AppItem(
                            appKey = app.appKey,
                            packageName = app.packageName,
                            name = app.name,
                            uid = app.uid,
                            icon = app.icon,
                            systemApp = app.systemApp,
                            profileId = app.profileId,
                            workProfile = app.workProfile,
                            routeKey = DEFAULT_ROUTE_OPTION.key,
                            routeKind = DEFAULT_ROUTE_OPTION.kind,
                            customRouted = false,
                            routeTitle = DEFAULT_ROUTE_OPTION.title,
                            routeDescription = DEFAULT_ROUTE_OPTION.description,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
                installedApps.value = apps
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Package metadata is optional UI data; keep the previous snapshot on failure.
            } finally {
                if (loadAppsRunId == runId) {
                    loadAppsJob = null
                    _isLoadingApps.value = false
                }
            }
        }
    }

    private fun refreshProviderRouting() {
        if (routingRefreshJob?.isActive == true) return
        routingRefreshJob = viewModelScope.launch {
            try {
                providerRoutingCoordinator.refreshSelectedServer()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Provider refresh is best-effort; the persisted routing remains usable.
            } finally {
                routingRefreshJob = null
            }
        }
    }

    fun setAppRoute(app: AppItem, option: AppRouteOption) {
        viewModelScope.launch {
            val assignment = option.toRouteAssignment() ?: return@launch
            appBypassDao.upsert(
                assignment.toAppBypassEntity(
                    packageName = app.packageName,
                    profileId = app.profileId,
                    uid = app.uid,
                    manual = option.kind != AppRouteKind.DEFAULT,
                ),
            )
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
    }

    fun setShowWorkProfileApps(show: Boolean) {
        _showWorkProfileApps.value = show
    }

    fun setAppSpecificServerNoteShown() {
        viewModelScope.launch {
            settingsRepository.setAppSpecificServerNoteShown(true)
        }
    }

    fun switchToManualRouting() {
        viewModelScope.launch {
            settingsRepository.setRoutingPolicyControl(RoutingPolicyControl.User)
        }
    }

    fun bypassAllApps() {
        viewModelScope.launch {
            installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.Direct).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    ),
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun resetAllToDefault() {
        viewModelScope.launch {
            installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.DefaultSelected).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    ),
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    private fun AppItem.resolveRouteOption(
        assignment: AppBypassEntity?,
        serverOptionsById: Map<Long, AppRouteOption>,
        useRootService: Boolean,
    ): AppRouteOption {
        if (assignment == null) return DEFAULT_ROUTE_OPTION
        if (!useRootService) {
            return when (assignment.routeAssignment()) {
                AppRouteAssignment(AppRouteMode.Direct),
                AppRouteAssignment(AppRouteMode.Bypass),
                -> DIRECT_ROUTE_OPTION
                else -> DEFAULT_ROUTE_OPTION
            }
        }
        return when (val routeAssignment = assignment.routeAssignment()) {
            AppRouteAssignment(AppRouteMode.Direct) -> DIRECT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.Bypass) -> BYPASS_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultOutbound) -> INHERIT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultSelected) -> DEFAULT_ROUTE_OPTION
            else -> {
                val serverId = routeAssignment.serverId ?: return DEFAULT_ROUTE_OPTION
                serverOptionsById[serverId] ?: AppRouteOption(
                    key = serverRouteKey(serverId),
                    title = AppRouteText.Resource(R.string.apps_route_missing_server_title),
                    description = AppRouteText.Resource(R.string.apps_route_missing_server_description),
                    kind = AppRouteKind.SERVER,
                    serverId = serverId,
                )
            }
        }
    }

    private fun ServerEntity.toRouteOption(): AppRouteOption {
        val config = runCatching { serverRepository.parseConfig(this) }.getOrNull()
        val outboundCount = config?.proxyOutboundCount()
        val description = when {
            outboundCount != null -> AppRouteText.PluralResource(
                resourceId = R.plurals.apps_server_multiconnect_summary,
                quantity = outboundCount,
                arguments = listOf(outboundCount),
            )
            config != null -> AppRouteText.Raw(config.endpointSummary())
            else -> AppRouteText.Resource(
                R.string.apps_server_endpoint_unknown,
                listOf(protocol.lowercase(java.util.Locale.ROOT)),
            )
        }
        return AppRouteOption(
            key = serverRouteKey(id),
            title = AppRouteText.Raw(name),
            description = description,
            kind = AppRouteKind.SERVER,
            serverId = id,
        )
    }

    companion object {
        private const val INHERIT_ROUTE_KEY = "inherit"
        private const val DEFAULT_ROUTE_KEY = "default"
        private const val DIRECT_ROUTE_KEY = "direct"
        private const val BYPASS_ROUTE_KEY = "bypass"

        val INHERIT_ROUTE_OPTION = AppRouteOption(
            key = INHERIT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_default_outbound_title),
            description = AppRouteText.Resource(R.string.apps_route_default_outbound_description),
            kind = AppRouteKind.INHERIT,
        )
        val DEFAULT_ROUTE_OPTION = AppRouteOption(
            key = DEFAULT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_default_server_title),
            description = AppRouteText.Resource(R.string.apps_route_default_server_description),
            kind = AppRouteKind.DEFAULT,
        )
        val DIRECT_ROUTE_OPTION = AppRouteOption(
            key = DIRECT_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_not_proxied_title),
            description = AppRouteText.Resource(R.string.apps_route_not_proxied_description),
            kind = AppRouteKind.DIRECT,
        )
        val BYPASS_ROUTE_OPTION = AppRouteOption(
            key = BYPASS_ROUTE_KEY,
            title = AppRouteText.Resource(R.string.apps_route_bypass_tun_title),
            description = AppRouteText.Resource(R.string.apps_route_bypass_tun_description),
            kind = AppRouteKind.BYPASS,
        )

        fun serverRouteKey(serverId: Long): String = "server:$serverId"

        private fun AppRouteOption.toRouteAssignment(): AppRouteAssignment? = when (kind) {
            AppRouteKind.INHERIT -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
            AppRouteKind.DEFAULT -> AppRouteAssignment(AppRouteMode.DefaultSelected)
            AppRouteKind.DIRECT -> AppRouteAssignment(AppRouteMode.Direct)
            AppRouteKind.BYPASS -> AppRouteAssignment(AppRouteMode.Bypass)
            AppRouteKind.SERVER -> serverId?.let { AppRouteAssignment(AppRouteMode.Server, it) }
        }
    }
}
