package com.material.xray.core.app

private const val PER_USER_RANGE = 100_000
private const val APP_ID_MIN = 10_000
private const val APP_ID_MAX = 99_999

data class AppIdentity(
    val profileId: Int,
    val packageName: String,
) {
    val key: String = appKey(profileId, packageName)
}

fun appKey(profileId: Int, packageName: String): String = "$profileId:$packageName"

fun parseAppKey(value: String): AppIdentity {
    val profile = value.substringBefore(':').toIntOrNull()
    val packageName = value.substringAfter(':', missingDelimiterValue = value)
    return if (profile != null && packageName != value) {
        AppIdentity(profileId = profile, packageName = packageName)
    } else {
        AppIdentity(profileId = 0, packageName = value)
    }
}

fun profileIdForUid(uid: Int): Int = if (uid > 0) uid / PER_USER_RANGE else 0

fun isApplicationUid(uid: Int): Boolean {
    if (uid <= 0) return false
    val appId = uid % PER_USER_RANGE
    return appId in APP_ID_MIN..APP_ID_MAX
}

fun uidForProfile(profileId: Int, uid: Int): Int {
    if (uid <= 0) return uid
    val appId = uid % PER_USER_RANGE
    return profileId.coerceAtLeast(0) * PER_USER_RANGE + appId
}

fun appUidRangeForProfile(profileId: Int): IntRange {
    val base = profileId.coerceAtLeast(0) * PER_USER_RANGE
    return (base + APP_ID_MIN)..(base + APP_ID_MAX)
}
