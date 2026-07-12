package com.material.xray.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object ApplyingRoutingChanges : ConnectionState
    data object UpdatingRoutingData : ConnectionState
    data class RestartRequired(val tunName: String) : ConnectionState
    data class InterfaceBusy(val tunName: String) : ConnectionState
    data class Connected(
        val serverName: String,
        val corePid: Int,
        val tunName: String,
        val physicalInterface: String,
        val physicalGateway: String? = null,
        val physicalTable: String? = null,
        val startTime: Long = System.currentTimeMillis(),
    ) : ConnectionState
    data object Disconnecting : ConnectionState
    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : ConnectionState
}
