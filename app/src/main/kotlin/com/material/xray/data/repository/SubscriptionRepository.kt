package com.material.xray.data.repository

import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.parser.FetchedSubscription
import com.material.xray.data.parser.SubscriptionFetcher
import com.material.xray.model.withSubscriptionMetadata
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

    suspend fun refresh(subId: Long, url: String) {
        val existing = subscriptionDao.getById(subId) ?: return
        val fetched = fetcher.fetchWithMetadata(url)

        serverDao.deleteBySubscription(subId)
        serverDao.insertAll(
            fetched.configs.mapIndexed { index, config ->
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
        )

        subscriptionDao.update(existing.applyFetchedData(fetched))
    }

    suspend fun refreshAll() {
        subscriptionDao.getAll().forEach { sub ->
            runCatching { refresh(sub.id, sub.url) }
        }
    }

    suspend fun delete(sub: SubscriptionEntity) {
        subscriptionDao.delete(sub)
    }

    suspend fun update(sub: SubscriptionEntity, name: String, url: String) {
        val updated = sub.copy(
            name = name.trim().ifEmpty { nextFallbackName(excludingId = sub.id) },
            url = url.trim(),
        )
        subscriptionDao.update(updated)
        refresh(updated.id, updated.url)
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

    private companion object {
        val FALLBACK_NAME_PATTERN = Regex("""Subscription \d+""")
    }
}
