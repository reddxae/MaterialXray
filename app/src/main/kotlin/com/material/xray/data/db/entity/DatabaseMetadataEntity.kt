package com.material.xray.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "database_metadata")
data class DatabaseMetadataEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val valueValidationRevision: Int = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
