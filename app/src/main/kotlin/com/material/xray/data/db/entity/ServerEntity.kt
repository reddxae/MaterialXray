package com.material.xray.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subscriptionId")],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val configJson: String,
    val latencyMs: Int = -1,
    val sortOrder: Int = 0,
)
