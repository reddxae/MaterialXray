package com.material.xray.model

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

private fun String?.trimToNull(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }
