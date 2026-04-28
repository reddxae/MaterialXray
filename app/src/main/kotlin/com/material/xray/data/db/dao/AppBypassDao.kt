package com.material.xray.data.db.dao

import androidx.room.*
import com.material.xray.data.db.entity.AppBypassEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBypassDao {
    @Query("SELECT * FROM app_bypass ORDER BY packageName")
    fun observeAll(): Flow<List<AppBypassEntity>>

    @Query("SELECT * FROM app_bypass WHERE excluded = 1")
    suspend fun getExcluded(): List<AppBypassEntity>

    @Query("SELECT * FROM app_bypass WHERE excluded = 0 AND serverId IS NOT NULL")
    suspend fun getProxyAssignments(): List<AppBypassEntity>

    @Query("SELECT * FROM app_bypass WHERE excluded = 0 AND serverId IS NULL")
    suspend fun getDefaultProxyAssignments(): List<AppBypassEntity>

    @Upsert
    suspend fun upsert(entity: AppBypassEntity)

    @Query("DELETE FROM app_bypass WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_bypass")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AppBypassEntity>)
}
