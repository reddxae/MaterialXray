package com.materialxray.data.repository

import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.db.dao.SubscriptionDao
import com.materialxray.data.db.entity.ServerEntity
import com.materialxray.data.db.entity.SubscriptionEntity
import com.materialxray.data.parser.FetchedSubscription
import com.materialxray.data.parser.SubscriptionFetcher
import com.materialxray.model.withSubscriptionMetadata
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
        val id = subscriptionDao.insert(
            SubscriptionEntity(
                name = name,
                url = url,
            )
        )
        refresh(id, url)
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

    private fun SubscriptionEntity.applyFetchedData(fetched: FetchedSubscription): SubscriptionEntity =
        withSubscriptionMetadata(
            metadata = fetched.metadata,
            resolvedUrl = fetched.permanentRedirectUrl
                ?.takeIf { it.isNotBlank() }
                ?: url,
            lastUpdated = System.currentTimeMillis(),
        )
}
