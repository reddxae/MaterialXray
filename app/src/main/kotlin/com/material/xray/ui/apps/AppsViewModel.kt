package com.material.xray.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.service.PendingRoutingChange
import com.material.xray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val systemApp: Boolean,
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
    @ApplicationContext private val context: Context,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val routingChangeManager: RoutingChangeManager,
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

    val routeOptions: StateFlow<List<AppRouteOption>> = serverRepository.observeAll()
        .map { servers ->
            buildList {
                add(INHERIT_ROUTE_OPTION)
                add(DEFAULT_ROUTE_OPTION)
                add(DIRECT_ROUTE_OPTION)
                servers.forEach { server -> add(server.toRouteOption()) }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(INHERIT_ROUTE_OPTION, DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION),
        )

    val apps: StateFlow<List<AppItem>> = combine(
        _installedApps,
        bypassedApps,
        _searchQuery,
        _showSystemApps,
        routeOptions,
    ) { installed, assignments, query, showSystemApps, options ->
        val assignmentByPackage = assignments.associateBy { it.packageName }
        val serverOptionsById = options
            .filter { it.kind == AppRouteKind.SERVER && it.serverId != null }
            .associateBy { requireNotNull(it.serverId) }
        installed
            .map { app ->
                val assignment = assignmentByPackage[app.packageName]
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
                query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<AppItem> { !it.manuallyRouted }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.packageName },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = runCatching {
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filterNot { it.packageName == context.packageName }
                        .map { info ->
                            AppItem(
                                packageName = info.packageName,
                                name = info.loadLabel(pm).toString(),
                                uid = info.uid,
                                icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                                systemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
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
            when (option.kind) {
                AppRouteKind.INHERIT -> appBypassDao.upsert(
                    AppBypassEntity(
                        packageName = app.packageName,
                        uid = app.uid,
                        excluded = false,
                        serverId = null,
                        manual = true,
                        routeMode = ROUTE_MODE_DEFAULT_OUTBOUND,
                    )
                )
                AppRouteKind.DEFAULT -> appBypassDao.upsert(
                    AppBypassEntity(
                        packageName = app.packageName,
                        uid = app.uid,
                        excluded = false,
                        serverId = null,
                        manual = true,
                        routeMode = ROUTE_MODE_DEFAULT_SELECTED,
                    )
                )
                AppRouteKind.DIRECT -> appBypassDao.upsert(
                    AppBypassEntity(
                        packageName = app.packageName,
                        uid = app.uid,
                        excluded = true,
                        serverId = null,
                        manual = true,
                        routeMode = ROUTE_MODE_DIRECT,
                    )
                )
                AppRouteKind.SERVER -> {
                    val serverId = option.serverId ?: return@launch
                    appBypassDao.upsert(
                        AppBypassEntity(
                            packageName = app.packageName,
                            uid = app.uid,
                            excluded = false,
                            serverId = serverId,
                            manual = true,
                            routeMode = ROUTE_MODE_SERVER,
                        )
                    )
                }
            }
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
                    AppBypassEntity(
                        packageName = it.packageName,
                        uid = it.uid,
                        excluded = true,
                        serverId = null,
                        manual = false,
                        routeMode = ROUTE_MODE_DIRECT,
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
                    AppBypassEntity(
                        packageName = it.packageName,
                        uid = it.uid,
                        excluded = false,
                        serverId = null,
                        manual = false,
                        routeMode = ROUTE_MODE_DEFAULT_SELECTED,
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
        if (assignment.excluded) return DIRECT_ROUTE_OPTION
        if (assignment.routeMode == ROUTE_MODE_DEFAULT_OUTBOUND) return INHERIT_ROUTE_OPTION
        val serverId = assignment.serverId ?: return DEFAULT_ROUTE_OPTION
        return serverOptionsById[serverId] ?: AppRouteOption(
            key = serverRouteKey(serverId),
            title = "Missing server",
            description = "This app is assigned to a deleted configuration.",
            kind = AppRouteKind.SERVER,
            serverId = serverId,
        )
    }

    private fun ServerEntity.toRouteOption(): AppRouteOption {
        val config = runCatching { serverRepository.parseConfig(this) }.getOrNull()
        val description = config?.let {
            "${it.protocol.displayName.uppercase()} | ${it.transport.type.uppercase()} | ${it.security.type.uppercase()}"
        } ?: "$protocol | UNKNOWN"
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
        private const val ROUTE_MODE_DEFAULT_OUTBOUND = "default_outbound"
        private const val ROUTE_MODE_DEFAULT_SELECTED = "default_selected"
        private const val ROUTE_MODE_DIRECT = "direct"
        private const val ROUTE_MODE_SERVER = "server"

        val INHERIT_ROUTE_OPTION = AppRouteOption(
            key = INHERIT_ROUTE_KEY,
            title = "Default outbound",
            description = "Use the default outbound selected in Settings.",
            kind = AppRouteKind.INHERIT,
        )
        val DEFAULT_ROUTE_OPTION = AppRouteOption(
            key = DEFAULT_ROUTE_KEY,
            title = "Default selected server",
            description = "Use the server selected on Home.",
            kind = AppRouteKind.DEFAULT,
        )
        val DIRECT_ROUTE_OPTION = AppRouteOption(
            key = DIRECT_ROUTE_KEY,
            title = "Not proxied",
            description = "Bypass the TUN interface and use the device network.",
            kind = AppRouteKind.DIRECT,
        )

        fun serverRouteKey(serverId: Long): String = "server:$serverId"
    }
}
