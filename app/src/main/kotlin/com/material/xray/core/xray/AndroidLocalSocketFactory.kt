package com.material.xray.core.xray

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

internal class AndroidLocalSocketFactory(
    socketName: String,
    namespace: LocalSocketAddress.Namespace,
) : SocketFactory() {
    private val socketAddress = LocalSocketAddress(socketName, namespace)

    override fun createSocket(): Socket = AndroidLocalSocket(socketAddress)

    override fun createSocket(host: String, port: Int): Socket = createAndConnect()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createAndConnect()

    override fun createSocket(host: InetAddress, port: Int): Socket = createAndConnect()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createAndConnect()

    private fun createAndConnect(): Socket = createSocket().apply {
        connect(InetSocketAddress(0))
    }
}

@Suppress("UnsynchronizedOverridesSynchronized")
private class AndroidLocalSocket(
    private val socketAddress: LocalSocketAddress,
) : Socket() {
    private val localSocket = LocalSocket()
    private var closed = false
    private var inputShutdown = false
    private var outputShutdown = false

    override fun bind(bindpoint: SocketAddress?) = Unit

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // Shutdown is best-effort: a torn-down peer can make either call throw, and that must
        // not keep the underlying descriptor from being released below.
        if (!inputShutdown) runCatching { shutdownInput() }
        if (!outputShutdown) runCatching { shutdownOutput() }
        localSocket.close()
    }

    override fun connect(endpoint: SocketAddress?) {
        localSocket.connect(socketAddress)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        localSocket.connect(socketAddress, timeout)
    }

    override fun getChannel(): SocketChannel = unsupported("getChannel")

    override fun getInetAddress(): InetAddress = unsupported("getInetAddress")

    override fun getInputStream(): InputStream = object : FilterInputStream(localSocket.inputStream) {
        override fun close() = this@AndroidLocalSocket.close()
    }

    override fun getKeepAlive(): Boolean = unsupported("getKeepAlive")

    override fun getLocalAddress(): InetAddress = unsupported("getLocalAddress")

    override fun getLocalPort(): Int = unsupported("getLocalPort")

    override fun getLocalSocketAddress(): SocketAddress = object : SocketAddress() {}

    override fun getOOBInline(): Boolean = unsupported("getOOBInline")

    override fun getOutputStream(): OutputStream = object : FilterOutputStream(localSocket.outputStream) {
        override fun close() = this@AndroidLocalSocket.close()
    }

    override fun getPort(): Int = unsupported("getPort")

    override fun getReceiveBufferSize(): Int = try {
        localSocket.receiveBufferSize
    } catch (e: IOException) {
        throw e.toSocketException()
    }

    override fun getRemoteSocketAddress(): SocketAddress = object : SocketAddress() {}

    override fun getReuseAddress(): Boolean = unsupported("getReuseAddress")

    override fun getSendBufferSize(): Int = try {
        localSocket.sendBufferSize
    } catch (e: IOException) {
        throw e.toSocketException()
    }

    override fun getSoLinger(): Int = -1

    override fun getSoTimeout(): Int = try {
        localSocket.soTimeout
    } catch (e: IOException) {
        throw e.toSocketException()
    }

    override fun getTcpNoDelay(): Boolean = true

    override fun getTrafficClass(): Int = unsupported("getTrafficClass")

    override fun isBound(): Boolean = localSocket.isBound

    @Synchronized
    override fun isClosed(): Boolean = closed

    override fun isConnected(): Boolean = localSocket.isConnected

    @Synchronized
    override fun isInputShutdown(): Boolean = inputShutdown

    @Synchronized
    override fun isOutputShutdown(): Boolean = outputShutdown

    override fun sendUrgentData(data: Int) = unsupported<Unit>("sendUrgentData")

    override fun setKeepAlive(on: Boolean) = unsupported<Unit>("setKeepAlive")

    override fun setOOBInline(on: Boolean) = unsupported<Unit>("setOOBInline")

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) = Unit

    override fun setReceiveBufferSize(size: Int) {
        try {
            localSocket.receiveBufferSize = size
        } catch (e: IOException) {
            throw e.toSocketException()
        }
    }

    override fun setReuseAddress(on: Boolean) = unsupported<Unit>("setReuseAddress")

    override fun setSendBufferSize(size: Int) {
        try {
            localSocket.sendBufferSize = size
        } catch (e: IOException) {
            throw e.toSocketException()
        }
    }

    override fun setSoLinger(on: Boolean, linger: Int) = unsupported<Unit>("setSoLinger")

    override fun setSoTimeout(timeout: Int) {
        try {
            localSocket.soTimeout = timeout
        } catch (e: IOException) {
            throw e.toSocketException()
        }
    }

    override fun setTcpNoDelay(on: Boolean) = Unit

    override fun setTrafficClass(tc: Int) = unsupported<Unit>("setTrafficClass")

    @Synchronized
    override fun shutdownInput() {
        localSocket.shutdownInput()
        inputShutdown = true
    }

    @Synchronized
    override fun shutdownOutput() {
        localSocket.shutdownOutput()
        outputShutdown = true
    }

    private fun IOException.toSocketException(): SocketException = SocketException().also { socketException ->
        socketException.initCause(this)
    }

    private fun <T> unsupported(operation: String): T = throw UnsupportedOperationException("$operation is not supported by Android local sockets")
}
