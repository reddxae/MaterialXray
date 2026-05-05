package com.material.xray.ui.home

import com.material.xray.data.db.entity.SubscriptionEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
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
)

internal data class SubscriptionExpiryUiState(
    val inlineText: String,
    val standaloneText: String,
)

internal data class MetadataTextSegment(
    val value: String,
    val emphasized: Boolean,
)

internal fun buildSubscriptionMetadataUiState(
    subscription: SubscriptionEntity,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionMetadataUiState =
    SubscriptionMetadataUiState(
        announcement = subscription.announce?.trim().orEmpty(),
        traffic = buildSubscriptionTrafficUiState(
            upload = subscription.subscriptionUploadBytes,
            download = subscription.subscriptionDownloadBytes,
            total = subscription.subscriptionTotalBytes,
        ),
        expiry = subscription.subscriptionExpireAt?.let { expireAt ->
            formatSubscriptionExpiryUiState(
                epochSeconds = expireAt,
                clock = clock,
                zoneId = zoneId,
            )
        },
        updateIntervalText = formatAutoUpdateInterval(subscription.autoUpdateIntervalHours),
    )

internal fun SubscriptionTrafficUiState.summaryText(expiry: SubscriptionExpiryUiState?): String =
    if (quotaText == null && expiry != null) {
        "$summary, ${expiry.inlineText}"
    } else {
        summary
    }

internal fun SubscriptionTrafficUiState.detailText(expiry: SubscriptionExpiryUiState?): String? {
    val downloaded = downloadText
    return when {
        quotaText == null -> null
        downloaded != null && expiry != null -> "$downloaded, ${expiry.inlineText}"
        downloaded != null -> downloaded
        expiry != null -> expiry.inlineText
        else -> null
    }
}

internal fun buildSubscriptionTrafficUiState(
    upload: Long?,
    download: Long?,
    total: Long?,
): SubscriptionTrafficUiState? {
    if (upload == null && download == null && total == null) return null

    val downloaded = download?.coerceAtLeast(0) ?: 0L
    val downloadText = "$DOWNLOAD_TRAFFIC_PREFIX ${formatGigabyteCount(downloaded)}"

    return when {
        total == null || total <= 0 -> SubscriptionTrafficUiState(
            summary = if (download == null) {
                INFINITE_TRAFFIC_TEXT
            } else {
                "$INFINITE_TRAFFIC_TEXT, $downloadText"
            },
            downloadText = download?.let { downloadText },
        )

        else -> SubscriptionTrafficUiState(
            summary = "${formatGigabyteCount(downloaded)} of ${formatGigabyteCount(total)}",
            quotaText = formatGigabyteCount(total),
            progress = (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
            downloadText = downloadText,
        )
    }
}

internal fun formatSubscriptionExpiryUiState(
    epochSeconds: Long,
    clock: Clock = Clock.systemDefaultZone(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SubscriptionExpiryUiState? {
    if (epochSeconds <= 0) return null

    val now = clock.instant()
    val expiresAt = normalizeSubscriptionExpireInstant(epochSeconds, zoneId) ?: return null
    if (expiresAt.isAfter(now.plus(LONG_TERM_SUBSCRIPTION_DURATION))) return null
    if (!expiresAt.isAfter(now)) {
        return SubscriptionExpiryUiState(
            inlineText = "expired",
            standaloneText = "Expired",
        )
    }

    val formattedDate = SUBSCRIPTION_EXPIRY_DATE_FORMATTER.format(
        expiresAt.atZone(zoneId).toLocalDate(),
    )
    return SubscriptionExpiryUiState(
        inlineText = "expires on $formattedDate",
        standaloneText = "Expires on $formattedDate",
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

internal fun formatAutoUpdateInterval(intervalHours: Int): String =
    when (intervalHours) {
        0 -> "Manual update only"
        1 -> "Auto update every hour"
        24 -> "Auto update every day"
        72 -> "Auto update every 3 days"
        else -> "Auto update every $intervalHours hours"
    }

internal fun metadataTextSegments(text: String): List<MetadataTextSegment> {
    val segments = mutableListOf<MetadataTextSegment>()
    var startIndex = 0

    while (startIndex < text.length) {
        val nextToken = text.findNextEmphasizedToken(startIndex)
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

private fun parseBasicDateExpireInstant(value: Long, zoneId: ZoneId): Instant? =
    try {
        LocalDate.parse(value.toString(), DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(zoneId)
            .toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

private fun formatGigabyteCount(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble() / BYTES_PER_GB
    val formatted = if (value == 0.0 || value >= 10.0 && value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$formatted GB"
}

private fun String.findNextEmphasizedToken(startIndex: Int): EmphasizedToken? {
    val arrowIndex = indexOf(DOWNLOAD_TRAFFIC_PREFIX, startIndex)
    val expiredIndex = indexOf(EXPIRED_STATUS_TEXT, startIndex, ignoreCase = true)

    return listOfNotNull(
        arrowIndex.takeIf { it >= 0 }?.let {
            EmphasizedToken(it until it + DOWNLOAD_TRAFFIC_PREFIX.length, DOWNLOAD_TRAFFIC_PREFIX)
        },
        expiredIndex.takeIf { it >= 0 }?.let {
            val value = substring(it, it + EXPIRED_STATUS_TEXT.length)
            EmphasizedToken(it until it + value.length, value)
        },
    ).minByOrNull { it.range.first }
}

private const val INFINITE_TRAFFIC_TEXT = "∞ traffic"
private const val DOWNLOAD_TRAFFIC_PREFIX = "↓"
private const val EXPIRED_STATUS_TEXT = "expired"
private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
private val SUBSCRIPTION_EXPIRY_YEAR_RANGE = 2000L..9999L
private val SUBSCRIPTION_EXPIRY_BASIC_DATE_RANGE = 20_000_000L..99_991_231L
private val LONG_TERM_SUBSCRIPTION_DURATION: Duration = Duration.ofDays(365)
private val SUBSCRIPTION_EXPIRY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)
