package com.material.xray.model

data class AppUpdate(
    val tagName: String,
    val apkDownloadUrl: String?,
)

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
