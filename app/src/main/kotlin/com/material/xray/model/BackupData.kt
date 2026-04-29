package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupData(
    val version: Int = 2,
    val subscriptions: List<BackupSubscription>,
    val servers: List<BackupServer> = emptyList(),
    val bypassedApps: List<String>,
    val settings: Map<String, String>,
) {
    @Serializable
    data class BackupSubscription(
        val name: String,
        val url: String,
        val autoUpdateIntervalHours: Int = 1,
        val descriptionHidden: Boolean = false,
        val metadata: SubscriptionMetadata? = null,
    )

    @Serializable
    data class BackupServer(
        val subscriptionUrl: String?,
        val config: ServerConfig,
    )
}
