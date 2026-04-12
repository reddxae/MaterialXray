package com.materialxray.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0,
    val contentDisposition: String? = null,
    val contentType: String? = null,
    val profileTitle: String? = null,
    val profileUpdateIntervalHours: Int? = null,
    val subscriptionUploadBytes: Long? = null,
    val subscriptionDownloadBytes: Long? = null,
    val subscriptionTotalBytes: Long? = null,
    val subscriptionExpireAt: Long? = null,
    val profileWebPageUrl: String? = null,
    val announce: String? = null,
    val supportUrl: String? = null,
)
