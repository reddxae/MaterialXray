package com.material.xray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.data.repository.ServerRepository
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

    @Inject lateinit var serverRepository: ServerRepository

    @Inject lateinit var rootShell: RootShell

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            // A detached coroutine has no other handler; an escaped exception here would crash
            // the whole app process in the middle of boot handling.
            runCatching { autoConnectIfConfigured(context) }
                .onFailure { error -> Log.e(TAG, "Auto-connect after boot failed", error) }
            pendingResult.finish()
        }
    }

    private suspend fun autoConnectIfConfigured(context: Context) {
        val autoConnect = settingsRepo.autoConnect.first()
        if (!autoConnect) return

        val useRootService = settingsRepo.useRootService.first()
        if (useRootService) {
            CleanupManager(context, rootShell).ensureCleanState()
        }

        val lastServerId = settingsRepo.lastServerId.first()
        if (lastServerId < 0) return

        val serverEntity = serverRepository.getById(lastServerId) ?: return
        val config = serverRepository.parseConfig(serverEntity)
        XrayService.connect(context, config)
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
