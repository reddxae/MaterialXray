package com.materialxray.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.materialxray.core.root.RootShell
import com.materialxray.core.xray.CleanupManager
import com.materialxray.data.db.dao.ServerDao
import com.materialxray.data.repository.SettingsRepository
import com.materialxray.model.ServerConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var serverDao: ServerDao
    @Inject lateinit var rootShell: RootShell

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CleanupManager(context, rootShell).ensureCleanState()

                val autoConnect = settingsRepo.autoConnect.first()
                if (!autoConnect) return@launch

                val lastServerId = settingsRepo.lastServerId.first()
                if (lastServerId < 0) return@launch

                val serverEntity = serverDao.getById(lastServerId) ?: return@launch
                val config = Json.decodeFromString<ServerConfig>(serverEntity.configJson)
                XrayService.connect(context, config)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
