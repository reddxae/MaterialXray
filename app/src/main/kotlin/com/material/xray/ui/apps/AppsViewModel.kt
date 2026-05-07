package com.material.xray.ui.apps

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.core.app.AppInventory
import com.material.xray.core.app.appKey
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.endpointSummary
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val appKey: String,
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val systemApp: Boolean,
    val profileId: Int,
    val profileLabel: String,
    val workProfile: Boolean,
    val routeKey: String,
    val routeKind: AppRouteKind,
    val manuallyRouted: Boolean,
    val routeTitle: String,
    val routeDescription: String,
)

enum class AppRouteKind {
    INHERIT,
    DEFAULT,
    DIRECT,
    BYPASS,
    SERVER,
}

data class AppRouteOption(
    val key: String,
    val title: String,
    val description: String,
    val kind: AppRouteKind,
    val serverId: Long? = null,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
    private val appInventory: AppInventory,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _showSystemApps = MutableStateFlow(false)
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps

    val appSpecificServerNoteShown: StateFlow<Boolean> = settingsRepository.appSpecificServerNoteShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val bypassedApps = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())

    val routeOptions: StateFlow<List<AppRouteOption>> = combine(
        serverRepository.observeAll(),
        settingsRepository.showAdvancedOptions,
    ) { servers, showAdvancedOptions ->
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

    val apps: StateFlow<List<AppItem>> = combine(
        _installedApps,
        bypassedApps,
        _searchQuery,
        _showSystemApps,
        routeOptions,
    ) { installed, assignments, query, showSystemApps, options ->
        val assignmentByApp = assignments.associateBy { appKey(it.profileId, it.packageName) }
        val serverOptionsById = options
            .filter { it.kind == AppRouteKind.SERVER && it.serverId != null }
            .associateBy { requireNotNull(it.serverId) }
        installed
            .map { app ->
                val assignment = assignmentByApp[app.appKey]
                val option = app.resolveRouteOption(assignment, serverOptionsById)
                app.copy(
                    routeKey = option.key,
                    routeKind = option.kind,
                    manuallyRouted = assignment?.manual == true,
                    routeTitle = option.title,
                    routeDescription = option.description,
                )
            }
            .filter { showSystemApps || !it.systemApp }
            .filter {
                query.isEmpty() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true) ||
                    it.profileLabel.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<AppItem> { !it.manuallyRouted }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.profileId }
                    .thenBy { it.packageName },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { loadApps() }

    fun refreshApps() {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = runCatching {
                withContext(Dispatchers.IO) {
                    appInventory.loadInstalledApps()
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
                                profileLabel = app.profileLabel,
                                workProfile = app.workProfile,
                                routeKey = DEFAULT_ROUTE_OPTION.key,
                                routeKind = DEFAULT_ROUTE_OPTION.kind,
                                manuallyRouted = false,
                                routeTitle = DEFAULT_ROUTE_OPTION.title,
                                routeDescription = DEFAULT_ROUTE_OPTION.description,
                            )
                        }
                        .sortedBy { it.name.lowercase() }
                }
            }.getOrDefault(emptyList())
            _installedApps.value = apps
            _isLoadingApps.value = false
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
                    manual = true,
                )
            )
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
    }

    fun setAppSpecificServerNoteShown() {
        viewModelScope.launch {
            settingsRepository.setAppSpecificServerNoteShown(true)
        }
    }

    fun routeAllDirect() {
        viewModelScope.launch {
            _installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.Direct).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    )
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun resetAllToDefault() {
        viewModelScope.launch {
            _installedApps.value.forEach {
                appBypassDao.upsert(
                    AppRouteAssignment(AppRouteMode.DefaultSelected).toAppBypassEntity(
                        packageName = it.packageName,
                        profileId = it.profileId,
                        uid = it.uid,
                        manual = false,
                    )
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    private fun AppItem.resolveRouteOption(
        assignment: AppBypassEntity?,
        serverOptionsById: Map<Long, AppRouteOption>,
    ): AppRouteOption {
        if (assignment == null) return DEFAULT_ROUTE_OPTION
        return when (val routeAssignment = assignment.routeAssignment()) {
            AppRouteAssignment(AppRouteMode.Direct) -> DIRECT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.Bypass) -> BYPASS_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultOutbound) -> INHERIT_ROUTE_OPTION
            AppRouteAssignment(AppRouteMode.DefaultSelected) -> DEFAULT_ROUTE_OPTION
            else -> {
                val serverId = routeAssignment.serverId ?: return DEFAULT_ROUTE_OPTION
                serverOptionsById[serverId] ?: AppRouteOption(
                    key = serverRouteKey(serverId),
                    title = "Missing server",
                    description = "This app is assigned to a deleted configuration.",
                    kind = AppRouteKind.SERVER,
                    serverId = serverId,
                )
            }
        }
    }

    private fun ServerEntity.toRouteOption(): AppRouteOption {
        val config = runCatching { serverRepository.parseConfig(this) }.getOrNull()
        val description = config?.endpointSummary() ?: "${protocol.lowercase()} • unknown"
        return AppRouteOption(
            key = serverRouteKey(id),
            title = name,
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
            title = "Default outbound",
            description = "Use the default outbound selected on Settings page.",
            kind = AppRouteKind.INHERIT,
        )
        val DEFAULT_ROUTE_OPTION = AppRouteOption(
            key = DEFAULT_ROUTE_KEY,
            title = "Default selected server",
            description = "Use the server selected on Home page.",
            kind = AppRouteKind.DEFAULT,
        )
        val DIRECT_ROUTE_OPTION = AppRouteOption(
            key = DIRECT_ROUTE_KEY,
            title = "Not proxied",
            description = "Use the device network.",
            kind = AppRouteKind.DIRECT,
        )
        val BYPASS_ROUTE_OPTION = AppRouteOption(
            key = BYPASS_ROUTE_KEY,
            title = "Bypass TUN",
            description = "Bypass the TUN interface entirely.",
            kind = AppRouteKind.BYPASS,
        )

        fun serverRouteKey(serverId: Long): String = "server:$serverId"

        private fun AppRouteOption.toRouteAssignment(): AppRouteAssignment? =
            when (kind) {
                AppRouteKind.INHERIT -> AppRouteAssignment(AppRouteMode.DefaultOutbound)
                AppRouteKind.DEFAULT -> AppRouteAssignment(AppRouteMode.DefaultSelected)
                AppRouteKind.DIRECT -> AppRouteAssignment(AppRouteMode.Direct)
                AppRouteKind.BYPASS -> AppRouteAssignment(AppRouteMode.Bypass)
                AppRouteKind.SERVER -> serverId?.let { AppRouteAssignment(AppRouteMode.Server, it) }
            }
    }
}
