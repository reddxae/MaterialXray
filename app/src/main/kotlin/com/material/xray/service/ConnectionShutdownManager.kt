package com.material.xray.service

import android.content.Context
import com.material.xray.R
import com.material.xray.core.locale.localizedString
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ConnectionShutdownManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val stateCoordinator: ConnectionStateCoordinator,
) {
    suspend fun disconnectIfRunning() {
        if (!stateCoordinator.state.value.requiresRuntimeDisconnect()) return

        stateCoordinator.markDisconnecting()
        try {
            XrayService.disconnect(context, force = true)
        } catch (error: IllegalStateException) {
            markStopFailure()
            throw error
        } catch (error: SecurityException) {
            markStopFailure()
            throw error
        }
        if (
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                stateCoordinator.state.first { !it.requiresRuntimeDisconnect() }
            } == null
        ) {
            markStopFailure()
            error("Timed out waiting for the active connection to stop")
        }
    }

    private fun markStopFailure() {
        stateCoordinator.markError(context.localizedString(R.string.connection_error_stop_runtime), retryable = false)
    }

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
