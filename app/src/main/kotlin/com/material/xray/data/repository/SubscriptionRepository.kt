package com.material.xray.data.repository

import androidx.room.withTransaction
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.parser.FetchedSubscription
import com.material.xray.data.parser.ShareLinkParser
import com.material.xray.data.parser.SubscriptionFetcher
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionRequestIdentity
import com.material.xray.model.SubscriptionRouting
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.parseSubscriptionHeaders
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SubscriptionRepository @Inject constructor(
    private val database: AppDatabase,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val fetcher: SubscriptionFetcher,
    private val settingsRepository: SettingsRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val shareLinkParser = ShareLinkParser()
    private val refreshLocks = List(REFRESH_LOCK_COUNT) { Mutex() }

    fun observeAll(): Flow<List<SubscriptionEntity>> = combine(
        subscriptionDao.observeAll(),
        settingsRepository.legacySubscriptionPreferJson,
    ) { subscriptions, legacyPreferJson ->
        subscriptions.map { subscription ->
            if (subscription.preferJson == null) {
                subscription.copy(preferJson = legacyPreferJson)
            } else {
                subscription
            }
        }
    }

    data class RefreshResult(
        val subscriptionId: Long,
        val serverIdByConfigJson: Map<String, Long>,
        val serverIdReplacements: Map<Long, Long>,
        val appRouting: SubscriptionAppRouting? = null,
        val routing: SubscriptionRouting? = null,
    )

    data class RefreshBatchResult(
        val successes: Map<Long, RefreshResult>,
        val failures: Map<Long, IOException>,
    )

    internal data class PreparedRefresh(
        val subscriptionId: Long,
        val fetched: FetchedSubscription,
        val servers: List<ServerEntity>,
    )

    suspend fun add(
        name: String,
        url: String,
        preferJson: Boolean = true,
        userAgentMode: SubscriptionUserAgentMode = SubscriptionUserAgentMode.default,
        customUserAgent: String = "",
        customHeaders: String = "",
    ): Long {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        shareLinkParser.parse(trimmedUrl)?.let { config ->
            return addServerConfig(
                name = trimmedName,
                sourceLink = trimmedUrl,
                preferJson = preferJson,
                config = config,
            )
        }

        val id = subscriptionDao.insert(
            SubscriptionEntity(
                name = trimmedName.ifEmpty { nextFallbackName() },
                url = trimmedUrl,
                preferJson = preferJson,
                userAgentMode = userAgentMode.value,
                customUserAgent = customUserAgent.trim().ifBlank { null },
                customHeaders = customHeaders.trim().ifBlank { null },
            ),
        )
        refresh(id, trimmedUrl)
        return id
    }

    suspend fun addLink(link: String): Long = add(name = "", url = link)

    private suspend fun addServerConfig(
        name: String,
        sourceLink: String,
        preferJson: Boolean,
        config: ServerConfig,
    ): Long {
        val subscriptionName = name.trim()
            .ifEmpty { config.name.trim() }
            .ifEmpty { nextFallbackName() }
        val id = subscriptionDao.insert(
            SubscriptionEntity(
                name = subscriptionName,
                url = sourceLink,
                preferJson = preferJson,
                lastUpdated = System.currentTimeMillis(),
                autoUpdateIntervalHours = 0,
            ),
        )
        serverDao.insertAll(
            listOf(
                ServerEntity(
                    subscriptionId = id,
                    name = config.name,
                    protocol = config.protocol.name,
                    address = config.address,
                    port = config.port,
                    configJson = json.encodeToString(config),
                    sortOrder = 0,
                ),
            ),
        )
        return id
    }

    private suspend fun refresh(subId: Long, url: String): RefreshResult? = withRefreshLock(subId) {
        prepareRefresh(subId, url)?.let { commitRefresh(it) }
    }

    internal suspend fun prepareRefresh(subId: Long, url: String): PreparedRefresh? {
        val existing = subscriptionDao.getById(subId) ?: return null
        val identity = existing.requestIdentity(settingsRepository.subscriptionSendHardwareId.first())
        val fetched = fetcher.fetchWithMetadata(
            url = url,
            identity = identity,
            preferJson = existing.preferJson ?: settingsRepository.legacySubscriptionPreferJson.first(),
        )
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

        return PreparedRefresh(
            subscriptionId = subId,
            fetched = fetched,
            servers = servers,
        )
    }

    internal suspend fun commitRefresh(prepared: PreparedRefresh): RefreshResult? {
        val subId = prepared.subscriptionId
        return database.withTransaction {
            val current = subscriptionDao.getById(subId) ?: return@withTransaction null
            val existingServers = serverDao.getBySubscription(subId)
            serverDao.deleteBySubscription(subId)
            val insertedIds = if (prepared.servers.isEmpty()) emptyList() else serverDao.insertAll(prepared.servers)
            subscriptionDao.update(current.applyFetchedData(prepared.fetched))
            val insertedServers = prepared.servers.zip(insertedIds).map { (server, id) -> server.copy(id = id) }
            RefreshResult(
                subscriptionId = subId,
                serverIdByConfigJson = insertedServers
                    .associate { server -> server.configJson to server.id },
                serverIdReplacements = buildServerIdReplacements(existingServers, insertedServers),
                appRouting = prepared.fetched.appRouting,
                routing = prepared.fetched.routing,
            )
        }
    }

    internal suspend fun getAllSubscriptions(): List<SubscriptionEntity> = subscriptionDao.getAll()

    internal suspend fun <T> withRefreshLock(subscriptionId: Long, block: suspend () -> T): T = refreshLock(subscriptionId).withLock { block() }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionDao.delete(sub)
    }

    suspend fun setAutoUpdateInterval(subId: Long, intervalHours: Int) {
        subscriptionDao.updateAutoUpdateInterval(subId, intervalHours.coerceAtLeast(0))
    }

    suspend fun setDescriptionHidden(subId: Long, hidden: Boolean) {
        subscriptionDao.updateDescriptionHidden(subId, hidden)
    }

    internal suspend fun updateBeforeRefresh(sub: SubscriptionEntity, name: String, url: String): SubscriptionEntity {
        val updated = sub.copy(
            name = name.trim().ifEmpty { nextFallbackName(excludingId = sub.id) },
            url = url.trim(),
        )
        subscriptionDao.update(updated)
        return updated
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
        ).withSubscriptionAppRouting(fetched.appRouting)
            .withSubscriptionRouting(fetched.routing)
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

    private fun SubscriptionEntity.requestIdentity(sendHardwareId: Boolean): SubscriptionRequestIdentity = SubscriptionRequestIdentity(
        mode = SubscriptionUserAgentMode.fromValue(userAgentMode),
        sendHardwareId = sendHardwareId,
        customUserAgent = customUserAgent.orEmpty(),
        customHeaders = parseSubscriptionHeaders(customHeaders.orEmpty()),
    )

    private fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.isFallbackSubscriptionName(): Boolean = matches(FALLBACK_NAME_PATTERN)

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

    private fun List<ServerEntity>.uniqueByTrimmedName(): Map<String, Long> = asSequence()
        .map { it.name.trim() to it.id }
        .filter { (name, _) -> name.isNotEmpty() }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .mapValues { (_, ids) -> ids.single() }

    private fun List<ServerEntity>.uniqueTrimmedNames(): Set<String> = asSequence()
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it == 1 }
        .keys

    private fun refreshLock(subscriptionId: Long): Mutex = refreshLocks[
        Math.floorMod(subscriptionId.hashCode(), refreshLocks.size),
    ]

    private companion object {
        const val REFRESH_LOCK_COUNT = 32
        val FALLBACK_NAME_PATTERN = Regex("""Subscription \d+""")
    }
}

internal fun SubscriptionEntity.isDueForRefresh(nowMillis: Long): Boolean {
    if (lastUpdated <= 0L) return true

    val interval = autoUpdateIntervalHours
    if (interval <= 0) return false

    val intervalMillis = interval * MILLIS_PER_HOUR
    return nowMillis - lastUpdated >= intervalMillis
}

private const val MILLIS_PER_HOUR = 60L * 60L * 1000L
