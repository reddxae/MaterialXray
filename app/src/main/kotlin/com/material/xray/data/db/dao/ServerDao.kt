package com.material.xray.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.material.xray.data.db.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // A DAO is a data access surface; each query is one method.
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY subscriptionId, sortOrder")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder")
    fun observeBySubscription(subId: Long): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortOrder")
    suspend fun getBySubscription(subId: Long): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: Long): ServerEntity?

    @Query("SELECT * FROM servers ORDER BY subscriptionId, sortOrder")
    suspend fun getAll(): List<ServerEntity>

    @Insert
    suspend fun insertAll(servers: List<ServerEntity>): List<Long>

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    @Query("UPDATE servers SET latencyMs = :latency WHERE id = :id")
    suspend fun updateLatency(id: Long, latency: Int)

    /**
     * Writes a locally edited config back. The denormalised columns are rewritten alongside
     * [configJson] so the list summary, latency tests and app routing keep matching what was saved,
     * and the latency is cleared because the edit may have moved the endpoint it was measured
     * against.
     */
    @Query(
        "UPDATE servers SET name = :name, protocol = :protocol, address = :address, " +
            "port = :port, configJson = :configJson, edited = 1, latencyMs = -1 WHERE id = :id",
    )
    suspend fun updateEditedConfig(
        id: Long,
        name: String,
        protocol: String,
        address: String,
        port: Int,
        configJson: String,
    )

    @Query("UPDATE servers SET guarded = :guarded WHERE id = :id")
    suspend fun updateGuarded(id: Long, guarded: Boolean)

    @Transaction
    suspend fun updateSortOrders(serverIds: List<Long>) {
        serverIds.forEachIndexed { sortOrder, serverId ->
            updateSortOrder(serverId, sortOrder)
        }
    }

    @Query("UPDATE servers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}
