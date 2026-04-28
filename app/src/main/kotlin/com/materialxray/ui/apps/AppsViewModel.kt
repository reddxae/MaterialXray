package com.materialxray.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.repository.ServerRepository
import com.materialxray.service.PendingRoutingChange
import com.materialxray.service.RoutingChangeManager
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
    val routeKey: String,
    val routeTitle: String,
    val routeDescription: String,
)

enum class AppRouteKind {
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
    private val routingChangeManager: RoutingChangeManager,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val bypassedApps = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())

    val routeOptions: StateFlow<List<AppRouteOption>> = serverRepository.observeAll()
        .map { servers ->
            buildList {
                add(DEFAULT_ROUTE_OPTION)
                add(DIRECT_ROUTE_OPTION)
                servers.forEach { server -> add(server.toRouteOption()) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(DEFAULT_ROUTE_OPTION, DIRECT_ROUTE_OPTION))

    val apps: StateFlow<List<AppItem>> = combine(
        _installedApps,
        bypassedApps,
        _searchQuery,
        routeOptions,
    ) { installed, assignments, query, options ->
        val assignmentByPackage = assignments.associateBy { it.packageName }
        val serverOptionsById = options
            .filter { it.kind == AppRouteKind.SERVER && it.serverId != null }
            .associateBy { requireNotNull(it.serverId) }
        installed
            .map { app ->
                val option = app.resolveRouteOption(assignmentByPackage[app.packageName], serverOptionsById)
                app.copy(
                    routeKey = option.key,
                    routeTitle = option.title,
                    routeDescription = option.description,
                )
            }
            .filter {
                query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<AppItem> { it.routeKey == DEFAULT_ROUTE_OPTION.key }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.packageName },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { info ->
                        AppItem(
                            packageName = info.packageName,
                            name = info.loadLabel(pm).toString(),
                            uid = info.uid,
                            icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                            routeKey = DEFAULT_ROUTE_OPTION.key,
                            routeTitle = DEFAULT_ROUTE_OPTION.title,
                            routeDescription = DEFAULT_ROUTE_OPTION.description,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
            _installedApps.value = apps
        }
    }

    fun setAppRoute(app: AppItem, option: AppRouteOption) {
        viewModelScope.launch {
            when (option.kind) {
                AppRouteKind.DEFAULT -> appBypassDao.delete(app.packageName)
                AppRouteKind.DIRECT -> appBypassDao.upsert(
                    AppBypassEntity(
                        packageName = app.packageName,
                        uid = app.uid,
                        excluded = true,
                        serverId = null,
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
                        )
                    )
                }
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun routeAllDirect() {
        viewModelScope.launch {
            _installedApps.value.forEach {
                appBypassDao.upsert(
                    AppBypassEntity(
                        packageName = it.packageName,
                        uid = it.uid,
                        excluded = true,
                        serverId = null,
                    )
                )
            }
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    fun resetAllToDefault() {
        viewModelScope.launch {
            appBypassDao.deleteAll()
            routingChangeManager.markPendingChanges(PendingRoutingChange.APP_ROUTING)
        }
    }

    private fun AppItem.resolveRouteOption(
        assignment: AppBypassEntity?,
        serverOptionsById: Map<Long, AppRouteOption>,
    ): AppRouteOption {
        if (assignment == null) return DEFAULT_ROUTE_OPTION
        if (assignment.excluded) return DIRECT_ROUTE_OPTION
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
        private const val DEFAULT_ROUTE_KEY = "default"
        private const val DIRECT_ROUTE_KEY = "direct"

        val DEFAULT_ROUTE_OPTION = AppRouteOption(
            key = DEFAULT_ROUTE_KEY,
            title = "Default selected config",
            description = "Use the configuration selected on Home.",
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
