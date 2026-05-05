package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.SubscriptionMetadata
import com.material.xray.model.SubscriptionUserInfo
import com.material.xray.model.normalized

fun SubscriptionEntity.toSubscriptionMetadata(): SubscriptionMetadata? =
    SubscriptionMetadata(
        contentDisposition = contentDisposition,
        contentType = contentType,
        profileTitle = profileTitle,
        profileUpdateIntervalHours = profileUpdateIntervalHours,
        subscriptionUserInfo = SubscriptionUserInfo(
            upload = subscriptionUploadBytes,
            download = subscriptionDownloadBytes,
            total = subscriptionTotalBytes,
            expire = subscriptionExpireAt,
        ).normalized(),
        profileWebPageUrl = profileWebPageUrl,
        announce = announce,
        supportUrl = supportUrl,
    ).normalized()

fun SubscriptionEntity.withSubscriptionMetadata(
    metadata: SubscriptionMetadata?,
    resolvedUrl: String = url,
    resolvedName: String = name,
    lastUpdated: Long = this.lastUpdated,
): SubscriptionEntity {
    val normalizedMetadata = metadata?.normalized()
    val userInfo = normalizedMetadata?.subscriptionUserInfo

    return copy(
        name = resolvedName,
        url = resolvedUrl,
        lastUpdated = lastUpdated,
        contentDisposition = normalizedMetadata?.contentDisposition,
        contentType = normalizedMetadata?.contentType,
        profileTitle = normalizedMetadata?.profileTitle,
        profileUpdateIntervalHours = normalizedMetadata?.profileUpdateIntervalHours,
        subscriptionUploadBytes = userInfo?.upload,
        subscriptionDownloadBytes = userInfo?.download,
        subscriptionTotalBytes = userInfo?.total,
        subscriptionExpireAt = userInfo?.expire,
        profileWebPageUrl = normalizedMetadata?.profileWebPageUrl,
        announce = normalizedMetadata?.announce,
        supportUrl = normalizedMetadata?.supportUrl,
    )
}
