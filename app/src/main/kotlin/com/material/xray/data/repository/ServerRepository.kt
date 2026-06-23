package com.material.xray.data.repository

import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.model.ServerConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<ServerEntity>> = serverDao.observeAll()

    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>> = serverDao.observeBySubscription(subId)

    suspend fun getById(id: Long): ServerEntity? = serverDao.getById(id)

    fun parseConfig(entity: ServerEntity): ServerConfig = json.decodeFromString(entity.configJson)

    suspend fun updateLatency(id: Long, latencyMs: Int) {
        serverDao.updateLatency(id, latencyMs)
    }
}
