package com.material.xray.model

data class AppUpdate(
    val tagName: String,
    val apkDownloadUrl: String?,
)

sealed interface AppUpdateCheckStatus {
    data object Starting : AppUpdateCheckStatus

    data class Fetching(
        val url: String,
    ) : AppUpdateCheckStatus

    data class RetryingAfterHttpError(
        val url: String,
        val statusCode: Int,
        val nextUrl: String,
    ) : AppUpdateCheckStatus

    data class RetryingAfterConnectionFailure(
        val url: String,
        val nextUrl: String,
    ) : AppUpdateCheckStatus

    data class RetryingAfterInvalidResponse(
        val url: String,
        val statusCode: Int,
        val nextUrl: String,
    ) : AppUpdateCheckStatus

    data class ReleaseReceived(
        val url: String,
        val statusCode: Int,
    ) : AppUpdateCheckStatus

    data object UpToDate : AppUpdateCheckStatus

    data class UpdateAvailable(
        val version: String,
    ) : AppUpdateCheckStatus

    data object Failed : AppUpdateCheckStatus
}

internal val AppUpdateCheckStatus.isInProgress: Boolean
    get() = this !is AppUpdateCheckStatus.UpToDate &&
        this !is AppUpdateCheckStatus.UpdateAvailable &&
        this !is AppUpdateCheckStatus.Failed

internal fun isReleaseNewer(latestTag: String, currentVersion: String): Boolean {
    val latest = latestTag.toReleaseVersion() ?: return false
    val current = currentVersion.toReleaseVersion() ?: return false
    val componentCount = maxOf(latest.size, current.size)

    repeat(componentCount) { index ->
        val comparison = latest.getOrElse(index) { 0L }.compareTo(current.getOrElse(index) { 0L })
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal fun isUpdateCheckDue(
    lastCheckAtMillis: Long,
    nowMillis: Long,
    minimumIntervalMillis: Long,
): Boolean = lastCheckAtMillis <= 0L ||
    nowMillis < lastCheckAtMillis ||
    nowMillis - lastCheckAtMillis >= minimumIntervalMillis

private fun String.toReleaseVersion(): List<Long>? {
    val normalized = trim().removePrefix("v").removePrefix("V")
    if (!RELEASE_VERSION_PATTERN.matches(normalized)) return null
    return normalized.split('.').map { it.toLongOrNull() ?: return null }
}

private val RELEASE_VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)*")
