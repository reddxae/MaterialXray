package com.material.xray.core.xray

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

object TunInterfaceDetector {
    fun isInterfaceUp(name: String): Boolean =
        runCatching { NetworkInterface.getByName(name)?.isUp == true }
            .getOrDefault(false)

    fun isVpnServiceActive(context: Context): Boolean =
        runCatching {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
}
