package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.SubscriptionRouting
import kotlinx.serialization.json.Json

private val providerRoutingJson = Json { ignoreUnknownKeys = true }

fun SubscriptionEntity.toSubscriptionRouting(): SubscriptionRouting? = runCatching {
    providerRouting
        ?.takeIf { it.isNotBlank() }
        ?.let { providerRoutingJson.decodeFromString<SubscriptionRouting>(it).normalized() }
}.getOrNull()

fun SubscriptionEntity.withSubscriptionRouting(routing: SubscriptionRouting?): SubscriptionEntity = copy(
    providerRouting = routing?.normalized()?.let { providerRoutingJson.encodeToString(SubscriptionRouting.serializer(), it) },
)
