package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    // Version 2 was omitted from older JSON because it was the default.
    val version: Int = 2,
    val subscriptions: List<BackupSubscription>,
    val servers: List<BackupServer> = emptyList(),
    val bypassedApps: List<String>,
    val settings: Map<String, String>,
    val appRoutes: List<BackupAppRoute> = emptyList(),
    val selectedServerKey: String? = null,
) {
    @Serializable
    data class BackupSubscription(
        val key: String? = null,
        val name: String,
        val url: String,
        val preferJson: Boolean? = null,
        val autoUpdateIntervalHours: Int = 1,
        val descriptionHidden: Boolean = false,
        val userAgentMode: String? = null,
        val customUserAgent: String? = null,
        val customHeaders: String? = null,
        val metadata: SubscriptionMetadata? = null,
        val appRouting: SubscriptionAppRouting? = null,
        val routing: SubscriptionRouting? = null,
    )

    @Serializable
    data class BackupServer(
        val key: String? = null,
        val subscriptionKey: String? = null,
        val subscriptionUrl: String?,
        val config: ServerConfig,
    )

    @Serializable
    data class BackupAppRoute(
        val packageName: String,
        val profileId: Int,
        val mode: String,
        val serverKey: String? = null,
        val manual: Boolean = true,
    )

    companion object {
        const val CURRENT_VERSION = 3
    }
}
