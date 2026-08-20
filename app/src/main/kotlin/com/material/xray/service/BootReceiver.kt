package com.material.xray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.XrayStateReadResult
import com.material.xray.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (!isAutoConnectTrigger(intent?.action)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // A detached coroutine has no other handler; an escaped exception here would crash
            // the whole app process in the middle of automatic connection handling.
            runCatching { autoConnectIfConfigured(context, intent?.action) }
                .onFailure { error -> Log.e(TAG, "Automatic connection failed", error) }
            pendingResult.finish()
        }
    }

    private suspend fun autoConnectIfConfigured(context: Context, action: String?) {
        val autoConnect = settingsRepo.autoConnect.first()
        val hasRecordedRuntime = action == Intent.ACTION_MY_PACKAGE_REPLACED &&
            StateFile(context).readResult() !is XrayStateReadResult.Absent
        val recoverAfterReplacement = shouldRecoverAfterPackageReplacement(
            action = action,
            autoConnect = autoConnect,
            hasRecordedRuntime = hasRecordedRuntime,
        )
        if (!autoConnect && !recoverAfterReplacement) return

        val useRootService = settingsRepo.useRootService.first()
        val vpnPermissionGranted = useRootService || VpnService.prepare(context) == null
        if (!shouldStartAutomaticConnection(autoConnect || recoverAfterReplacement, vpnPermissionGranted)) return

        if (recoverAfterReplacement) {
            XrayService.recoverAfterPackageReplacement(context)
        } else {
            XrayService.autoConnect(context)
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}

internal fun isAutoConnectTrigger(action: String?): Boolean = action == Intent.ACTION_BOOT_COMPLETED ||
    action == Intent.ACTION_MY_PACKAGE_REPLACED

internal fun shouldStartAutomaticConnection(
    enabled: Boolean,
    vpnPermissionGranted: Boolean,
): Boolean = enabled && vpnPermissionGranted

internal fun shouldRecoverAfterPackageReplacement(
    action: String?,
    autoConnect: Boolean,
    hasRecordedRuntime: Boolean,
): Boolean = action == Intent.ACTION_MY_PACKAGE_REPLACED && (autoConnect || hasRecordedRuntime)
