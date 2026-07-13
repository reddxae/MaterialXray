package com.material.xray.data.repository

import com.material.xray.core.app.appKey
import com.material.xray.core.app.parseAppKey
import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.model.BackupData
import com.material.xray.model.ServerConfig

data class BackupSummary(
    val subscriptionCount: Int,
    val serverCount: Int,
    val appRouteCount: Int,
)

class PreparedBackupImport internal constructor(
    internal val plan: BackupImportPlan,
) {
    val summary = BackupSummary(
        subscriptionCount = plan.subscriptions.size,
        serverCount = plan.servers.size,
        appRouteCount = plan.appRoutes.size,
    )
}

internal data class BackupImportPlan(
    val source: BackupData,
    val subscriptions: List<PlannedBackupSubscription>,
    val servers: List<PlannedBackupServer>,
    val appRoutes: List<PlannedBackupAppRoute>,
    val selectedServerKey: String?,
)

internal data class PlannedBackupSubscription(
    val key: String,
    val value: BackupData.BackupSubscription,
)

internal data class PlannedBackupServer(
    val key: String,
    val subscriptionKey: String,
    val config: ServerConfig,
    val sortOrder: Int,
)

internal data class PlannedBackupAppRoute(
    val packageName: String,
    val profileId: Int,
    val mode: AppRouteMode,
    val serverKey: String?,
    val manual: Boolean,
)

internal object BackupImportPlanner {
    fun create(backup: BackupData): BackupImportPlan {
        require(backup.version in 1..BackupData.CURRENT_VERSION) {
            "Unsupported backup version ${backup.version}"
        }

        val subscriptions = backup.subscriptions.mapIndexed { index, subscription ->
            val key = if (backup.version >= BackupData.CURRENT_VERSION) {
                requireNotNull(subscription.key?.takeIf(String::isNotBlank)) {
                    "Subscription ${index + 1} has no stable key"
                }
            } else {
                subscription.key?.takeIf(String::isNotBlank) ?: "legacy-subscription-$index"
            }
            PlannedBackupSubscription(key, subscription)
        }
        requireUnique(subscriptions.map { it.key }, "subscription key")

        val subscriptionsByUrl = subscriptions.groupBy { it.value.url }
        val serversPerSubscription = mutableMapOf<String, Int>()
        val servers = backup.servers.mapIndexed { index, server ->
            val key = if (backup.version >= BackupData.CURRENT_VERSION) {
                requireNotNull(server.key?.takeIf(String::isNotBlank)) {
                    "Server ${index + 1} has no stable key"
                }
            } else {
                server.key?.takeIf(String::isNotBlank) ?: "legacy-server-$index"
            }
            val subscriptionKey = server.subscriptionKey?.takeIf(String::isNotBlank)
                ?: server.subscriptionUrl?.let { url ->
                    val matches = subscriptionsByUrl[url].orEmpty()
                    require(matches.size == 1) {
                        "Server ${index + 1} does not identify exactly one subscription"
                    }
                    matches.single().key
                }
                ?: throw IllegalArgumentException("Server ${index + 1} has no subscription reference")
            require(subscriptions.any { it.key == subscriptionKey }) {
                "Server ${index + 1} references an unknown subscription"
            }
            val sortOrder = serversPerSubscription.getOrDefault(subscriptionKey, 0)
            serversPerSubscription[subscriptionKey] = sortOrder + 1
            PlannedBackupServer(key, subscriptionKey, server.config, sortOrder)
        }
        requireUnique(servers.map { it.key }, "server key")

        val routes = if (backup.version >= BackupData.CURRENT_VERSION || backup.appRoutes.isNotEmpty()) {
            backup.appRoutes.mapIndexed { index, route ->
                require(route.packageName.isNotBlank()) { "App route ${index + 1} has no package name" }
                require(route.profileId >= 0) { "App route ${index + 1} has an invalid profile" }
                val mode = AppRouteMode.entries.firstOrNull { candidate ->
                    candidate.name == route.mode || candidate.persistedValue == route.mode
                } ?: throw IllegalArgumentException("App route ${index + 1} has an invalid mode")
                if (mode == AppRouteMode.Server) {
                    require(route.serverKey != null && servers.any { it.key == route.serverKey }) {
                        "App route ${index + 1} references an unknown server"
                    }
                } else {
                    require(route.serverKey == null) {
                        "App route ${index + 1} has an unexpected server reference"
                    }
                }
                PlannedBackupAppRoute(
                    packageName = route.packageName,
                    profileId = route.profileId,
                    mode = mode,
                    serverKey = route.serverKey,
                    manual = route.manual,
                )
            }
        } else {
            backup.bypassedApps.map { value ->
                val identity = parseAppKey(value)
                require(identity.packageName.isNotBlank()) { "Legacy app route has no package name" }
                require(identity.profileId >= 0) { "Legacy app route has an invalid profile" }
                PlannedBackupAppRoute(
                    packageName = identity.packageName,
                    profileId = identity.profileId,
                    mode = AppRouteMode.Bypass,
                    serverKey = null,
                    manual = true,
                )
            }
        }
        requireUnique(routes.map { appKey(it.profileId, it.packageName) }, "app route")

        val selectedServerKey = backup.selectedServerKey?.takeIf(String::isNotBlank)
        require(selectedServerKey == null || servers.any { it.key == selectedServerKey }) {
            "Selected server references an unknown server"
        }

        return BackupImportPlan(
            source = backup,
            subscriptions = subscriptions,
            servers = servers,
            appRoutes = routes,
            selectedServerKey = selectedServerKey,
        )
    }

    private fun requireUnique(values: List<String>, label: String) {
        require(values.size == values.toSet().size) { "Backup contains duplicate $label values" }
    }
}
