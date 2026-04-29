package com.material.xray.data.db.dao

import androidx.room.*
import com.material.xray.data.db.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY subscriptionId, sortOrder")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder")
    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Insert
    suspend fun insertAll(servers: List<ServerEntity>): List<Long>

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Query("UPDATE servers SET latencyMs = :latency WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Int)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}
