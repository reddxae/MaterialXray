package com.material.xray.model

import com.material.xray.data.db.entity.SubscriptionEntity
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionMetadata(
    val contentDisposition: String? = null,
    val contentType: String? = null,
    val profileTitle: String? = null,
    val profileUpdateIntervalHours: Int? = null,
    val subscriptionUserInfo: SubscriptionUserInfo? = null,
    val profileWebPageUrl: String? = null,
    val announce: String? = null,
    val supportUrl: String? = null,
)

@Serializable
data class SubscriptionUserInfo(
    val upload: Long? = null,
    val download: Long? = null,
    val total: Long? = null,
    val expire: Long? = null,
)

fun SubscriptionMetadata.hasValues(): Boolean =
    contentDisposition != null ||
            contentType != null ||
            profileTitle != null ||
            profileUpdateIntervalHours != null ||
            subscriptionUserInfo?.hasValues() == true ||
            profileWebPageUrl != null ||
            announce != null ||
            supportUrl != null

fun SubscriptionMetadata.normalized(): SubscriptionMetadata? {
    val normalized = copy(
        contentDisposition = contentDisposition.trimToNull(),
        contentType = contentType.trimToNull(),
        profileTitle = profileTitle.trimToNull(),
        profileUpdateIntervalHours = profileUpdateIntervalHours?.takeIf { it > 0 },
        subscriptionUserInfo = subscriptionUserInfo?.normalized(),
        profileWebPageUrl = profileWebPageUrl.trimToNull(),
        announce = announce.trimToNull(),
        supportUrl = supportUrl.trimToNull(),
    )
    return normalized.takeIf { it.hasValues() }
}

fun SubscriptionUserInfo.hasValues(): Boolean =
    upload != null || download != null || total != null || expire != null

fun SubscriptionUserInfo.normalized(): SubscriptionUserInfo? {
    val normalized = copy(
        upload = upload?.takeIf { it >= 0 },
        download = download?.takeIf { it >= 0 },
        total = total?.takeIf { it >= 0 },
        expire = expire?.takeIf { it > 0 },
    )
    return normalized.takeIf { it.hasValues() }
}

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

private fun String?.trimToNull(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }
