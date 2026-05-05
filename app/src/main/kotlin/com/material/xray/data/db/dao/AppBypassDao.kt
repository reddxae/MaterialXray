package com.material.xray.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.material.xray.data.db.entity.AppBypassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBypassDao {
    @Query("SELECT * FROM app_bypass ORDER BY profileId, packageName")
    fun observeAll(): Flow<List<AppBypassEntity>>

    @Query("SELECT * FROM app_bypass ORDER BY profileId, packageName")
    suspend fun getAll(): List<AppBypassEntity>

    @Query("SELECT * FROM app_bypass WHERE excluded = 1")
    suspend fun getExcluded(): List<AppBypassEntity>

    @Query("SELECT * FROM app_bypass WHERE excluded = 0 AND serverId IS NOT NULL")
    suspend fun getProxyAssignments(): List<AppBypassEntity>

    @Query("SELECT * FROM app_bypass WHERE excluded = 0 AND serverId IS NULL AND (routeMode IS NULL OR routeMode = 'default_selected')")
    suspend fun getDefaultProxyAssignments(): List<AppBypassEntity>

    @Upsert
    suspend fun upsert(entity: AppBypassEntity)

    @Query("UPDATE app_bypass SET serverId = :newServerId WHERE serverId = :oldServerId")
    suspend fun updateServerId(oldServerId: Long, newServerId: Long)

    @Query("DELETE FROM app_bypass WHERE profileId = :profileId AND packageName = :packageName")
    suspend fun delete(profileId: Int, packageName: String)

    @Query("DELETE FROM app_bypass")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AppBypassEntity>)
}
