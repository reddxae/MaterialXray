package com.material.xray.core.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(
    val appKey: String,
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val systemApp: Boolean,
    val profileId: Int,
    val profileLabel: String,
    val workProfile: Boolean,
)

data class AppInventorySnapshot(
    val apps: List<InstalledApp>,
    val profileIds: Set<Int>,
)

interface AppInventorySource {
    suspend fun loadSnapshot(): AppInventorySnapshot
}

@Singleton
class AppInventory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppInventorySource {
    suspend fun loadInstalledApps(): List<InstalledApp> = loadSnapshot().apps

    override suspend fun loadSnapshot(): AppInventorySnapshot = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val currentProfileId = profileIdForUid(context.applicationInfo.uid)
        val profiles = userProfiles()
        val profileIds = (profiles.mapNotNull { it.identifierOrNull() } + currentProfileId).toSet()
        val appsByKey = linkedMapOf<String, InstalledApp>()

        pm.getInstalledApplications(0)
            .asSequence()
            .filterNot { it.packageName == context.packageName }
            .map { info ->
                info.toInstalledApp(
                    rawLabel = info.loadLabel(pm),
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    currentProfileId = currentProfileId,
                )
            }
            .forEach { app -> appsByKey[app.appKey] = app }

        val launcherApps = context.getSystemService(LauncherApps::class.java)
        if (launcherApps != null) {
            profiles
                .filter { it.identifierOrNull() != currentProfileId }
                .forEach { profile ->
                    runCatching { launcherApps.getActivityList(null, profile) }
                        .getOrDefault(emptyList())
                        .forEach { activity ->
                            val info = activity.applicationInfo
                            if (info.packageName == context.packageName) return@forEach
                            val app = info.toInstalledApp(
                                rawLabel = activity.label,
                                icon = runCatching { activity.getIcon(0) }.getOrNull(),
                                userHandle = profile,
                                currentProfileId = currentProfileId,
                            )
                            appsByKey.putIfAbsent(app.appKey, app)
                        }
                }
        }

        AppInventorySnapshot(
            apps = appsByKey.values.sortedWith(
                compareBy<InstalledApp> { it.name.lowercase() }
                    .thenBy { it.profileId }
                    .thenBy { it.packageName },
            ),
            profileIds = profileIds,
        )
    }

    private fun ApplicationInfo.toInstalledApp(
        rawLabel: CharSequence,
        icon: Drawable?,
        userHandle: UserHandle = Process.myUserHandle(),
        currentProfileId: Int,
    ): InstalledApp {
        val profileId = userHandle.identifierOrNull() ?: profileIdForUid(uid)
        val profileUid = uidForProfile(profileId, uid)
        val workProfile = profileId != currentProfileId
        val label = context.packageManager.getUserBadgedLabel(rawLabel, userHandle).toString()
            .ifBlank { packageName }
        val badgedIcon = icon?.let { context.packageManager.getUserBadgedIcon(it, userHandle) }
        return InstalledApp(
            appKey = appKey(profileId, packageName),
            packageName = packageName,
            name = label,
            uid = profileUid,
            icon = badgedIcon,
            systemApp = flags and ApplicationInfo.FLAG_SYSTEM != 0,
            profileId = profileId,
            profileLabel = if (workProfile) "Work profile" else "Personal",
            workProfile = workProfile,
        )
    }

    private fun userProfiles(): List<UserHandle> =
        runCatching {
            context.getSystemService(UserManager::class.java)?.userProfiles.orEmpty()
        }.getOrDefault(emptyList())
            .ifEmpty { listOf(Process.myUserHandle()) }

    private fun UserHandle.identifierOrNull(): Int? =
        runCatching {
            javaClass.getMethod("getIdentifier").invoke(this) as? Int
        }.getOrNull()
}
