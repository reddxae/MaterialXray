package com.material.xray.ui.home

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.material.xray.R
import com.material.xray.data.db.entity.SubscriptionEntity
import java.text.NumberFormat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class SubscriptionMetadataUiState(
    val announcement: String,
    val traffic: SubscriptionTrafficUiState?,
    val expiry: SubscriptionExpiryUiState?,
    val updateIntervalText: String,
) {
    val hasMetadata: Boolean
        get() = announcement.isNotEmpty() ||
            traffic != null ||
            expiry != null ||
            updateIntervalText.isNotBlank()
}

internal data class SubscriptionTrafficUiState(
    val summary: String,
    val quotaText: String? = null,
    val progress: Float = 0f,
    val downloadText: String? = null,
    val downloadSizeText: String? = null,
)

internal data class SubscriptionExpiryUiState(
    val inlineText: String,
    val standaloneText: String,
    val isExpired: Boolean = false,
)

internal data class MetadataTextSegment(
    val value: String,
    val emphasized: Boolean,
)

internal interface SubscriptionMetadataText {
    val locale: Locale

    fun getString(@StringRes resourceId: Int, vararg arguments: Any): String

    fun getQuantityString(@PluralsRes resourceId: Int, quantity: Int, vararg arguments: Any): String
}

private class AndroidSubscriptionMetadataText(
    private val resources: Resources,
) : SubscriptionMetadataText {
    override val locale: Locale = if (resources.configuration.locales.isEmpty) {
        Locale.getDefault()
    } else {
        resources.configuration.locales[0]
    }

    override fun getString(resourceId: Int, vararg arguments: Any): String = resources.getString(resourceId, *arguments)

    override fun getQuantityString(resourceId: Int, quantity: Int, vararg arguments: Any): String = resources.getQuantityString(resourceId, quantity, *arguments)
}

internal fun buildSubscriptionMetadataUiState(
    subscription: SubscriptionEntity,
    resources: Resources,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionMetadataUiState = buildSubscriptionMetadataUiState(
    subscription = subscription,
    text = AndroidSubscriptionMetadataText(resources),
    clock = clock,
    zoneId = zoneId,
)

internal fun buildSubscriptionMetadataUiState(
    subscription: SubscriptionEntity,
    text: SubscriptionMetadataText,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionMetadataUiState = SubscriptionMetadataUiState(
    announcement = subscription.announce?.trim().orEmpty(),
    traffic = buildSubscriptionTrafficUiState(
        upload = subscription.subscriptionUploadBytes,
        download = subscription.subscriptionDownloadBytes,
        total = subscription.subscriptionTotalBytes,
        text = text,
    ),
    expiry = subscription.subscriptionExpireAt?.let { expireAt ->
        formatSubscriptionExpiryUiState(
            epochSeconds = expireAt,
            text = text,
            clock = clock,
            zoneId = zoneId,
        )
    },
    updateIntervalText = formatAutoUpdateInterval(subscription.autoUpdateIntervalHours, text),
)

internal fun SubscriptionMetadataUiState.headerDetailText(resources: Resources): String? = headerDetailText(
    AndroidSubscriptionMetadataText(resources),
)

internal fun SubscriptionMetadataUiState.headerDetailText(text: SubscriptionMetadataText): String? {
    if (traffic?.quotaText != null) return null
    return traffic?.detailText(expiry, text) ?: expiry?.standaloneText
}

private fun SubscriptionTrafficUiState.detailText(
    expiry: SubscriptionExpiryUiState?,
    text: SubscriptionMetadataText,
): String? {
    val downloadedSize = downloadSizeText
    return when {
        downloadedSize != null && expiry?.isExpired == true -> text.getString(
            R.string.home_subscription_downloaded_expired,
            downloadedSize,
        )
        downloadedSize != null && expiry != null -> text.getString(
            R.string.home_subscription_downloaded_with_expiry,
            downloadedSize,
            expiry.inlineText,
        )
        downloadedSize != null -> downloadText
        expiry != null -> expiry.standaloneText
        else -> null
    }
}

internal fun buildSubscriptionTrafficUiState(
    upload: Long?,
    download: Long?,
    total: Long?,
    resources: Resources,
): SubscriptionTrafficUiState? = buildSubscriptionTrafficUiState(
    upload = upload,
    download = download,
    total = total,
    text = AndroidSubscriptionMetadataText(resources),
)

private fun buildSubscriptionTrafficUiState(
    upload: Long?,
    download: Long?,
    total: Long?,
    text: SubscriptionMetadataText,
): SubscriptionTrafficUiState? {
    if (upload == null && download == null && total == null) return null

    val downloaded = download?.coerceAtLeast(0) ?: 0L
    val downloadedSizeText = formatGigabyteCount(downloaded, text)
    val downloadText = text.getString(R.string.home_subscription_downloaded, downloadedSizeText)

    return when {
        total == null || total <= 0 -> SubscriptionTrafficUiState(
            summary = if (download == null) {
                text.getString(R.string.home_subscription_unlimited_traffic)
            } else {
                text.getString(R.string.home_subscription_unlimited_traffic_downloaded, downloadedSizeText)
            },
            downloadText = download?.let { downloadText },
            downloadSizeText = download?.let { downloadedSizeText },
        )

        else -> SubscriptionTrafficUiState(
            summary = text.getString(
                R.string.home_subscription_used_of_total,
                downloadedSizeText,
                formatGigabyteCount(total, text),
            ),
            quotaText = formatGigabyteCount(total, text),
            progress = (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
            downloadText = downloadText,
            downloadSizeText = downloadedSizeText,
        )
    }
}

internal fun formatSubscriptionExpiryUiState(
    epochSeconds: Long,
    resources: Resources,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionExpiryUiState? = formatSubscriptionExpiryUiState(
    epochSeconds = epochSeconds,
    text = AndroidSubscriptionMetadataText(resources),
    clock = clock,
    zoneId = zoneId,
)

internal fun formatSubscriptionExpiryUiState(
    epochSeconds: Long,
    text: SubscriptionMetadataText,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionExpiryUiState? {
    if (epochSeconds <= 0) return null

    val now = clock.instant()
    val expiresAt = normalizeSubscriptionExpireInstant(epochSeconds, zoneId) ?: return null
    if (expiresAt.isAfter(now.plus(LONG_TERM_SUBSCRIPTION_DURATION))) return null
    if (!expiresAt.isAfter(now)) {
        return SubscriptionExpiryUiState(
            inlineText = text.getString(R.string.home_subscription_expired_inline),
            standaloneText = text.getString(R.string.home_subscription_expired_standalone),
            isExpired = true,
        )
    }

    val daysRemaining = ChronoUnit.DAYS.between(
        now.atZone(zoneId).toLocalDate(),
        expiresAt.atZone(zoneId).toLocalDate(),
    ).toInt()
    if (daysRemaining == 0) {
        return SubscriptionExpiryUiState(
            inlineText = text.getString(R.string.home_subscription_expires_today_inline),
            standaloneText = text.getString(R.string.home_subscription_expires_today_standalone),
        )
    }
    return SubscriptionExpiryUiState(
        inlineText = text.getQuantityString(
            R.plurals.home_subscription_expires_in_days_inline,
            daysRemaining,
            daysRemaining,
        ),
        standaloneText = text.getQuantityString(
            R.plurals.home_subscription_expires_in_days_standalone,
            daysRemaining,
            daysRemaining,
        ),
    )
}

internal fun normalizeSubscriptionExpireInstant(
    value: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Instant? {
    val normalizedValue = value.coerceAtLeast(0)
    return runCatching {
        when {
            normalizedValue in SUBSCRIPTION_EXPIRY_BASIC_DATE_RANGE -> {
                parseBasicDateExpireInstant(normalizedValue, zoneId) ?: Instant.ofEpochSecond(normalizedValue)
            }

            normalizedValue in SUBSCRIPTION_EXPIRY_YEAR_RANGE -> {
                LocalDate.of(normalizedValue.toInt(), 12, 31)
                    .atStartOfDay(zoneId)
                    .toInstant()
            }

            normalizedValue >= EPOCH_MILLIS_THRESHOLD -> Instant.ofEpochMilli(normalizedValue)

            else -> Instant.ofEpochSecond(normalizedValue)
        }
    }.getOrNull()
}

internal fun formatAutoUpdateInterval(intervalHours: Int, resources: Resources): String = formatAutoUpdateInterval(intervalHours, AndroidSubscriptionMetadataText(resources))

private fun formatAutoUpdateInterval(intervalHours: Int, text: SubscriptionMetadataText): String = when (intervalHours) {
    0 -> text.getString(R.string.home_auto_update_manual)
    24, 72 -> {
        val days = intervalHours / 24
        text.getQuantityString(R.plurals.home_auto_update_every_days, days, days)
    }
    else -> text.getQuantityString(
        R.plurals.home_auto_update_every_hours,
        intervalHours,
        intervalHours,
    )
}

internal fun metadataTextSegments(
    text: String,
    expiredStatusText: String? = null,
): List<MetadataTextSegment> {
    val segments = mutableListOf<MetadataTextSegment>()
    var startIndex = 0

    while (startIndex < text.length) {
        val nextToken = text.findNextEmphasizedToken(startIndex, expiredStatusText)
        if (nextToken == null) {
            segments += MetadataTextSegment(text.substring(startIndex), emphasized = false)
            break
        }

        if (nextToken.range.first > startIndex) {
            segments += MetadataTextSegment(
                value = text.substring(startIndex, nextToken.range.first),
                emphasized = false,
            )
        }
        segments += MetadataTextSegment(nextToken.value, emphasized = true)
        startIndex = nextToken.range.last + 1
    }

    return segments
}

private data class EmphasizedToken(
    val range: IntRange,
    val value: String,
)

private fun parseBasicDateExpireInstant(value: Long, zoneId: ZoneId): Instant? = try {
    LocalDate.parse(value.toString(), DateTimeFormatter.BASIC_ISO_DATE)
        .atStartOfDay(zoneId)
        .toInstant()
} catch (_: DateTimeParseException) {
    null
}

private fun formatGigabyteCount(bytes: Long, text: SubscriptionMetadataText): String {
    val value = bytes.coerceAtLeast(0).toDouble() / BYTES_PER_GB
    val fractionDigits = if (value == 0.0 || value >= 10.0 && value % 1.0 == 0.0) 0 else 1
    val formatted = NumberFormat.getNumberInstance(text.locale).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }.format(value)
    return text.getString(R.string.home_gigabytes, formatted)
}

private fun String.findNextEmphasizedToken(
    startIndex: Int,
    expiredStatusText: String?,
): EmphasizedToken? {
    val arrowIndex = indexOf(DOWNLOAD_TRAFFIC_PREFIX, startIndex)
    val expiredIndex = expiredStatusText
        ?.takeIf { it.isNotEmpty() }
        ?.let { indexOf(it, startIndex, ignoreCase = true) }
        ?: -1

    return listOfNotNull(
        arrowIndex.takeIf { it >= 0 }?.let {
            EmphasizedToken(it until it + DOWNLOAD_TRAFFIC_PREFIX.length, DOWNLOAD_TRAFFIC_PREFIX)
        },
        expiredIndex.takeIf { it >= 0 }?.let {
            val value = substring(it, it + expiredStatusText.orEmpty().length)
            EmphasizedToken(it until it + value.length, value)
        },
    ).minByOrNull { it.range.first }
}

private const val DOWNLOAD_TRAFFIC_PREFIX = "↓"
private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
private val SUBSCRIPTION_EXPIRY_YEAR_RANGE = 2000L..9999L
private val SUBSCRIPTION_EXPIRY_BASIC_DATE_RANGE = 20_000_000L..99_991_231L
private val LONG_TERM_SUBSCRIPTION_DURATION: Duration = Duration.ofDays(365)
