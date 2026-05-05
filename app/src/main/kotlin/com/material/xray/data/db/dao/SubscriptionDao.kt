package com.material.xray.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.material.xray.data.db.entity.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY id")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: Long): SubscriptionEntity?

    @Insert
    suspend fun insert(sub: SubscriptionEntity): Long

    @Update
    suspend fun update(sub: SubscriptionEntity)

    @Query("UPDATE subscriptions SET autoUpdateIntervalHours = :intervalHours WHERE id = :id")
    suspend fun updateAutoUpdateInterval(id: Long, intervalHours: Int)

    @Query("UPDATE subscriptions SET descriptionHidden = :hidden WHERE id = :id")
    suspend fun updateDescriptionHidden(id: Long, hidden: Boolean)

    @Delete
    suspend fun delete(sub: SubscriptionEntity)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
