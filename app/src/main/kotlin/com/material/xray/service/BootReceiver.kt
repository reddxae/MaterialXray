package com.material.xray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var serverRepository: ServerRepository
    @Inject lateinit var rootShell: RootShell

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val useRootService = settingsRepo.useRootService.first()
                if (!useRootService) return@launch

                CleanupManager(context, rootShell).ensureCleanState()

                val autoConnect = settingsRepo.autoConnect.first()
                if (!autoConnect) return@launch

                val lastServerId = settingsRepo.lastServerId.first()
                if (lastServerId < 0) return@launch

                val serverEntity = serverRepository.getById(lastServerId) ?: return@launch
                val config = serverRepository.parseConfig(serverEntity)
                XrayService.connect(context, config)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
