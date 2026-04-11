package com.materialxray.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object ApplyingRoutingChanges : ConnectionState
    data object UpdatingRoutingData : ConnectionState
    data class Connected(
        val serverName: String,
        val corePid: Int,
        val tunName: String,
        val physicalInterface: String,
        val startTime: Long = System.currentTimeMillis(),
    ) : ConnectionState
    data object Disconnecting : ConnectionState
    data class Error(val message: String) : ConnectionState
}
