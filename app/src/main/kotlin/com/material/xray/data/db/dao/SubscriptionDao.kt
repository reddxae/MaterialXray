package com.material.xray.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.material.xray.data.db.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
@Suppress("TooManyFunctions") // A DAO is a data access surface; each query is one method.
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder, id")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Query(
        """
        SELECT subscriptions.requiresHardwareId
        FROM subscriptions
        INNER JOIN servers ON servers.subscriptionId = subscriptions.id
        WHERE servers.id = :serverId
        """,
    )
    fun observeRequiresHardwareIdForServer(serverId: Long): Flow<Boolean?>

    @Insert
    suspend fun insert(sub: SubscriptionEntity): Long

    @Transaction
    suspend fun insertAtEnd(sub: SubscriptionEntity): Long = insert(
        sub.copy(sortOrder = (getMaxSortOrder() ?: -1) + 1),
    )

    @Query("SELECT MAX(sortOrder) FROM subscriptions")
    suspend fun getMaxSortOrder(): Int?

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("UPDATE subscriptions SET autoUpdateIntervalHours = :intervalHours WHERE id = :id")
    suspend fun updateAutoUpdateInterval(id: Long, intervalHours: Int)

    @Query("UPDATE subscriptions SET descriptionHidden = :hidden WHERE id = :id")
    suspend fun updateDescriptionHidden(id: Long, hidden: Boolean)

    @Transaction
    suspend fun updateSortOrders(subscriptionIds: List<Long>) {
        subscriptionIds.forEachIndexed { sortOrder, subscriptionId ->
            updateSortOrder(subscriptionId, sortOrder)
        }
    }

    @Query("UPDATE subscriptions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Delete
    suspend fun delete(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
