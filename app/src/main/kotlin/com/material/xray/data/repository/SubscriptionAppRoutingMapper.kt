package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionAppRoutingMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val subscriptionRoutingJson = Json { ignoreUnknownKeys = true }

fun SubscriptionEntity.toSubscriptionAppRouting(): SubscriptionAppRouting? {
    val mode = SubscriptionAppRoutingMode.fromPersisted(appRoutingMode) ?: return null
    val packages = runCatching {
        if (appRoutingPackages.isNullOrBlank()) {
            emptyList()
        } else {
            subscriptionRoutingJson.decodeFromString(ListSerializer(String.serializer()), appRoutingPackages)
        }
    }.getOrDefault(emptyList())
    return SubscriptionAppRouting(packages, mode).normalized()
}

fun SubscriptionEntity.withSubscriptionAppRouting(routing: SubscriptionAppRouting?): SubscriptionEntity {
    val normalized = routing?.normalized()
    return copy(
        appRoutingPackages = normalized?.packageNames?.let {
            subscriptionRoutingJson.encodeToString(ListSerializer(String.serializer()), it)
        },
        appRoutingMode = normalized?.mode?.persistedValue,
    )
}
