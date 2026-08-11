package com.material.xray.core.xray

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class TproxyPortAllocator {
    fun allocate(count: Int, allowIpv6: Boolean): List<Int> {
        require(count in 1..255)
        val allocated = linkedSetOf<Int>()
        val bindAddress = InetAddress.getByName(if (allowIpv6) "::" else "0.0.0.0")
        repeat(MAX_ATTEMPTS) {
            if (allocated.size == count) return allocated.toList()
            ServerSocket(0, 1, bindAddress).use { tcpSocket ->
                val candidate = tcpSocket.localPort
                if (candidate in allocated) return@use
                val udpAvailable = runCatching {
                    DatagramSocket(candidate, bindAddress).use { }
                }.isSuccess
                if (udpAvailable) allocated += candidate
            }
        }
        error("Could not allocate $count free TCP/UDP ports for TPROXY")
    }

    private companion object {
        const val MAX_ATTEMPTS = 2048
    }
}
