package com.material.xray.service

import android.content.Context
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class ConnectionRuntimeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val stateCoordinator: ConnectionStateCoordinator,
) {
    private val stateFile = StateFile(context)
    private val activeConfigFile = context.filesDir.resolve("config.json")

    suspend fun reconcileState() {
        val detectedState = detectTunnelInterfaceState()
        val reconciledState = stateCoordinator.reconcileDetectedState(detectedState)
        if (reconciledState is ConnectionState.Connected && reconciledState.corePid > 0) {
            XrayService.restoreStatus(context)
        }
    }

    suspend fun readActiveConfig(): String? = withContext(Dispatchers.IO) {
        runCatching { activeConfigFile.takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun detectTunnelInterfaceState(): ConnectionState? = withContext(Dispatchers.IO) {
        if (!settingsRepository.useRootService.first()) return@withContext null

        val persistedState = stateFile.read()
        val activeTunName = persistedState
            ?.tunName
            ?.takeIf { it.isNotBlank() }
            ?: settingsRepository.tunName.first().trim().takeIf { it.isNotEmpty() }
            ?: return@withContext null
        if (!TunInterfaceDetector.isInterfaceUp(activeTunName)) return@withContext null
        if (activeTunName == AMBIGUOUS_TUN_NAME && TunInterfaceDetector.isVpnServiceActive(context)) {
            return@withContext ConnectionState.InterfaceBusy(activeTunName)
        }

        val persistedServerName = persistedState?.serverName?.takeIf { it.isNotBlank() }
        val selectedServerName = settingsRepository.lastServerId.first()
            .takeIf { it > 0 }
            ?.let { serverRepository.getById(it) }
            ?.let { entity -> runCatching { serverRepository.parseConfig(entity).name }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        ConnectionState.Connected(
            serverName = persistedServerName ?: selectedServerName ?: context.localizedString(R.string.home_selected_server),
            corePid = persistedState?.xrayPid ?: -1,
            tunName = activeTunName,
            physicalInterface = persistedState?.physicalInterface ?: "unknown",
            physicalGateway = persistedState?.physicalGateway,
            physicalTable = persistedState?.physicalTable,
            startTime = persistedState?.timestamp ?: System.currentTimeMillis(),
        )
    }

    private companion object {
        const val AMBIGUOUS_TUN_NAME = "tun0"
    }
}
