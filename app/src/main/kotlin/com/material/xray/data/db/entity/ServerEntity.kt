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
    /** Set once the user has saved a local edit to [configJson]. Drives the pencil badge. */
    val edited: Boolean = false,
    /** Kept verbatim across subscription refreshes instead of being replaced. Drives the shield badge. */
    val guarded: Boolean = false,
)
