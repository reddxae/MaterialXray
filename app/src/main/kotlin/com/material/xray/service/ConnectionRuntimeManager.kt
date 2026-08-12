package com.material.xray.service

import android.content.Context
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunInterfaceDetector
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.RootConnectionBackend
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
        when (val detection = detectRuntime()) {
            is RuntimeDetection.Observed -> {
                val reconciledState = stateCoordinator.reconcileDetectedState(detection.state)
                if (reconciledState is ConnectionState.Connected && reconciledState.corePid > 0) {
                    XrayService.restoreStatus(context)
                }
            }
            RuntimeDetection.RecordedRootRuntime -> {
                if (stateCoordinator.markRestoringRecordedRuntime()) XrayService.restoreStatus(context)
            }
            null -> stateCoordinator.reconcileDetectedState(null)
        }
    }

    suspend fun readActiveConfig(): String? = withContext(Dispatchers.IO) {
        runCatching { activeConfigFile.takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * What this process can say about a runtime on its own, without root.
     */
    private sealed interface RuntimeDetection {
        /** Directly observed here, so it may be reported as fact. */
        data class Observed(val state: ConnectionState) : RuntimeDetection

        /** Only recorded on disk. Verifying it requires root, so the service has to confirm it. */
        data object RecordedRootRuntime : RuntimeDetection
    }

    private suspend fun detectRuntime(): RuntimeDetection? = withContext(Dispatchers.IO) {
        val persistedState = stateFile.read()
        if (!settingsRepository.useRootService.first() && persistedState?.transitionGuard == null) return@withContext null
        val activeTunName = persistedState
            ?.tunName
            ?.takeIf { it.isNotBlank() }
            ?: settingsRepository.tunName.first().trim().takeIf { it.isNotEmpty() }
            ?: return@withContext null

        // A TPROXY runtime has no network interface to observe, so there is nothing this process can
        // check. Report it as unverified rather than inventing a connection from the record.
        val tproxyRecorded = persistedState?.transitionGuard != null ||
            persistedState?.rootConnectionBackend == RootConnectionBackend.Tproxy &&
            persistedState.tproxy != null
        if (tproxyRecorded) return@withContext RuntimeDetection.RecordedRootRuntime

        if (!TunInterfaceDetector.isInterfaceUp(activeTunName)) return@withContext null
        if (activeTunName == AMBIGUOUS_TUN_NAME && TunInterfaceDetector.isVpnServiceActive(context)) {
            return@withContext RuntimeDetection.Observed(ConnectionState.InterfaceBusy(activeTunName))
        }

        val persistedServerName = persistedState?.serverName?.takeIf { it.isNotBlank() }
        val selectedServerName = settingsRepository.lastServerId.first()
            .takeIf { it > 0 }
            ?.let { serverRepository.getById(it) }
            ?.let { entity -> runCatching { serverRepository.parseConfig(entity).name }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        RuntimeDetection.Observed(
            ConnectionState.Connected(
                serverName = persistedServerName
                    ?: selectedServerName
                    ?: context.localizedString(R.string.home_selected_server),
                corePid = persistedState?.xrayPid ?: -1,
                tunName = activeTunName,
                physicalInterface = persistedState?.physicalInterface ?: "unknown",
                physicalGateway = persistedState?.physicalGateway,
                physicalTable = persistedState?.physicalTable,
                startTime = persistedState?.timestamp ?: System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val AMBIGUOUS_TUN_NAME = "tun0"
    }
}
