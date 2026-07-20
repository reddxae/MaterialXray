package com.material.xray.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.data.repository.AppUpdateRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.AppUpdate
import com.material.xray.model.AppUpdateCheckStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AppUpdateChecker @Inject constructor(
    private val repository: AppUpdateRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: AppUpdateNotifier,
) {
    private val checkMutex = Mutex()

    suspend fun check(
        manual: Boolean = false,
        onStatus: suspend (AppUpdateCheckStatus) -> Unit = {},
    ): AppUpdate? = checkMutex.withLock {
        if (!manual && !settingsRepository.appUpdateChecksEnabled.first()) return@withLock null
        val minimumIntervalMillis = if (manual) 0L else MINIMUM_AUTOMATIC_CHECK_INTERVAL_MILLIS
        if (!repository.claimUpdateCheck(System.currentTimeMillis(), minimumIntervalMillis)) return@withLock null
        val update = repository.checkForUpdate(onStatus)
        if (!manual && !settingsRepository.appUpdateChecksEnabled.first()) {
            repository.clearAvailableUpdate()
            return@withLock null
        }
        if (update == null) {
            notifier.dismiss()
        } else if (!repository.wasNotified(update.tagName) && notifier.show(update)) {
            repository.markNotified(update.tagName)
        }
        update
    }

    private companion object {
        const val MINIMUM_AUTOMATIC_CHECK_INTERVAL_MILLIS = 60 * 60 * 1000L
    }
}

@Singleton
class AppUpdateNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun show(update: AppUpdate): Boolean {
        if (!canPostNotifications()) return false

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.localizedString(R.string.notification_channel_app_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
        if (notificationManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }

        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val openIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.localizedString(R.string.notification_app_update_text, update.tagName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.localizedString(R.string.notification_app_update_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_default_monochrome)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun dismiss() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 3
    }
}
