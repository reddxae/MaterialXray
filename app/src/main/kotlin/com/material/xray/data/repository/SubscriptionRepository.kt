package com.material.xray.data.repository

import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.parser.FetchedSubscription
import com.material.xray.data.parser.SubscriptionFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val fetcher: SubscriptionFetcher,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<SubscriptionEntity>> = subscriptionDao.observeAll()

    data class RefreshResult(
        val serverIdByConfigJson: Map<String, Long>,
        val serverIdReplacements: Map<Long, Long>,
    )

    suspend fun add(name: String, url: String): Long {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        val id = subscriptionDao.insert(
            SubscriptionEntity(
                name = trimmedName.ifEmpty { nextFallbackName() },
                url = trimmedUrl,
            )
        )
        refresh(id, trimmedUrl)
        return id
    }

    suspend fun refresh(subId: Long, url: String): RefreshResult? {
        val existing = subscriptionDao.getById(subId) ?: return null
        val existingServers = serverDao.getBySubscription(subId)
        val fetched = fetcher.fetchWithMetadata(url)
        val servers = fetched.configs.mapIndexed { index, config ->
            ServerEntity(
                subscriptionId = subId,
                name = config.name,
                protocol = config.protocol.name,
                address = config.address,
                port = config.port,
                configJson = json.encodeToString(config),
                sortOrder = index,
            )
        }

        serverDao.deleteBySubscription(subId)
        val insertedIds = serverDao.insertAll(servers)

        subscriptionDao.update(existing.applyFetchedData(fetched))
        val insertedServers = servers.zip(insertedIds).map { (server, id) -> server.copy(id = id) }
        return RefreshResult(
            serverIdByConfigJson = insertedServers
                .associate { server -> server.configJson to server.id },
            serverIdReplacements = buildServerIdReplacements(existingServers, insertedServers),
        )
    }

    suspend fun refreshAll(): Map<Long, RefreshResult> = buildMap {
        subscriptionDao.getAll().forEach { sub ->
            runCatching { refresh(sub.id, sub.url) }
                .getOrNull()
                ?.let { result -> put(sub.id, result) }
        }
    }

    suspend fun refreshDueSubscriptions(nowMillis: Long = System.currentTimeMillis()): Map<Long, RefreshResult> = buildMap {
        subscriptionDao.getAll()
            .filter { it.isDueForAutoUpdate(nowMillis) }
            .forEach { sub ->
                runCatching { refresh(sub.id, sub.url) }
                    .getOrNull()
                    ?.let { result -> put(sub.id, result) }
            }
    }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionDao.delete(sub)
    }

    suspend fun setAutoUpdateInterval(subId: Long, intervalHours: Int) {
        subscriptionDao.updateAutoUpdateInterval(subId, intervalHours.coerceAtLeast(0))
    }

    suspend fun setDescriptionHidden(subId: Long, hidden: Boolean) {
        subscriptionDao.updateDescriptionHidden(subId, hidden)
    }

    suspend fun update(sub: SubscriptionEntity, name: String, url: String): RefreshResult? {
        val updated = sub.copy(
            name = name.trim().ifEmpty { nextFallbackName(excludingId = sub.id) },
            url = url.trim(),
        )
        subscriptionDao.update(updated)
        return refresh(updated.id, updated.url)
    }

    private suspend fun SubscriptionEntity.applyFetchedData(fetched: FetchedSubscription): SubscriptionEntity {
        val providerName = fetched.metadata.profileTitle.trimToNull()
        return withSubscriptionMetadata(
            metadata = fetched.metadata,
            resolvedUrl = fetched.permanentRedirectUrl
                ?.takeIf { it.isNotBlank() }
                ?: url,
            resolvedName = resolveDisplayName(providerName),
            lastUpdated = System.currentTimeMillis(),
        )
    }

    private suspend fun SubscriptionEntity.resolveDisplayName(providerName: String?): String {
        val currentName = name.trim()
        val previousProviderName = profileTitle.trimToNull()
        val automaticName = currentName.isEmpty() ||
            currentName == previousProviderName ||
            currentName.isFallbackSubscriptionName()

        return when {
            !automaticName -> currentName
            providerName != null -> providerName
            currentName.isNotEmpty() -> currentName
            else -> nextFallbackName(excludingId = id)
        }
    }

    private suspend fun nextFallbackName(excludingId: Long? = null): String {
        val usedNames = subscriptionDao.getAll()
            .asSequence()
            .filterNot { it.id == excludingId }
            .map { it.name.trim() }
            .toSet()
        var index = 1
        while ("Subscription $index" in usedNames) {
            index++
        }
        return "Subscription $index"
    }

    private fun String?.trimToNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.isFallbackSubscriptionName(): Boolean =
        matches(FALLBACK_NAME_PATTERN)

    private fun buildServerIdReplacements(
        oldServers: List<ServerEntity>,
        newServers: List<ServerEntity>,
    ): Map<Long, Long> {
        val newServerIdByConfigJson = newServers.associate { it.configJson to it.id }
        val uniqueNewServerIdByName = newServers.uniqueByTrimmedName()
        val uniqueOldNames = oldServers.uniqueTrimmedNames()

        return oldServers.mapNotNull { oldServer ->
            val replacementId = newServerIdByConfigJson[oldServer.configJson]
                ?: oldServer.name.trim()
                    .takeIf { it.isNotEmpty() && it in uniqueOldNames }
                    ?.let(uniqueNewServerIdByName::get)
                ?: return@mapNotNull null

            oldServer.id to replacementId
        }.toMap()
    }

    private fun List<ServerEntity>.uniqueByTrimmedName(): Map<String, Long> =
        asSequence()
            .map { it.name.trim() to it.id }
            .filter { (name, _) -> name.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { (_, ids) -> ids.single() }

    private fun List<ServerEntity>.uniqueTrimmedNames(): Set<String> =
        asSequence()
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it == 1 }
            .keys

    private fun SubscriptionEntity.isDueForAutoUpdate(nowMillis: Long): Boolean {
        val interval = autoUpdateIntervalHours
        if (interval <= 0) return false

        val intervalMillis = interval * MILLIS_PER_HOUR
        return lastUpdated <= 0L || nowMillis - lastUpdated >= intervalMillis
    }

    private companion object {
        val FALLBACK_NAME_PATTERN = Regex("""Subscription \d+""")
        const val MILLIS_PER_HOUR = 60L * 60L * 1000L
    }
}
