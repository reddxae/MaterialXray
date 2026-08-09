package com.material.xray.service

import android.os.ParcelFileDescriptor
import com.material.xray.core.xray.XRAY_API_SOCKET_NAME_PREFIX
import com.material.xray.core.xray.XrayApiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Questions that can be asked about a core a runtime has started.
 *
 * A root-managed core is reached through the root shell, while a rootless core is a child of this
 * process, so even reading its memory use is runtime-specific.
 */
internal interface XrayRuntimeProcess {
    suspend fun isAlive(pid: Int): Boolean

    suspend fun kill(pid: Int, signal: Int): Boolean

    suspend fun readResidentMemoryMb(pid: Int): Long?

    suspend fun readActiveConnectionCount(pid: Int): Int?

    suspend fun readProcessMetrics(pid: Int): ProcessMetrics?

    suspend fun readCrashReason(): String
}

/**
 * The lifecycle operations that differ between a core managed through the root shell and one this
 * process launched itself.
 *
 * A root-managed core is an ordinary system process that outlives the app and owns routing state
 * installed outside it, while the rootless core is a child of this process holding a tunnel
 * descriptor. Resolving which of the two owns the runtime once, when it starts, is what keeps
 * every caller that merely wants to ask a question about "the core" from having to re-derive it.
 */
internal interface XrayRuntimeStrategy : XrayRuntimeProcess {
    /**
     * Whether this runtime installs routing outside the process: TUN interfaces, ip rules and a
     * bypass route for the physical link. The rootless runtime gets all of that from Android's
     * VpnService instead, so every step guarded by this property has nothing to do there.
     */
    val managesSystemRouting: Boolean

    /**
     * Reports the path the core is launched from, or null when it is not available.
     *
     * [verifyAvailable] is false for a reconnect that reuses an already-prepared runtime, where
     * the executable was checked moments ago and re-checking it means re-reading the asset.
     */
    suspend fun prepareBinary(verifyAvailable: Boolean): String?

    suspend fun prepareLogFile()

    /**
     * Launches the core. [vpnInterface] stays owned by the caller, so an implementation that needs
     * it must not suspend before the descriptor has been handed to the child.
     */
    suspend fun startProcess(binDir: String, vpnInterface: ParcelFileDescriptor?): Int

    /** Picks an address for the core's API that this runtime can actually reach. */
    fun nextApiEndpoint(environment: ConnectionEnvironment): XrayApiEndpoint

    /**
     * Stops the core and removes everything this runtime installed outside the process.
     *
     * [fastCleanup] permits removing only what the recorded state describes, which is enough when
     * that state is known to be current. Returns false when the cleanup itself failed, which is a
     * condition the next connection attempt has to repair.
     */
    suspend fun release(fastCleanup: Boolean): Boolean

    /** Stops the core without waiting, for use while the owning service is being destroyed. */
    fun requestStop()
}

internal class RootXrayRuntimeStrategy(
    private val processSupervisor: RootXrayProcessController,
    private val rootRuntime: ConnectionRootRuntime,
    private val cleanup: ConnectionCleanup,
    private val xrayBinary: ConnectionXrayBinary,
) : XrayRuntimeStrategy {
    override val managesSystemRouting = true

    override suspend fun prepareBinary(verifyAvailable: Boolean): String? {
        if (verifyAvailable && !xrayBinary.ensureRootBinaryExtracted()) return null
        return xrayBinary.rootBinaryPath
    }

    override suspend fun prepareLogFile() = processSupervisor.prepareLogFile()

    override suspend fun startProcess(binDir: String, vpnInterface: ParcelFileDescriptor?): Int = processSupervisor.start(binDir)

    // The root shell reaches the core over the loopback interface, which is then firewalled to
    // this app's uid.
    override fun nextApiEndpoint(environment: ConnectionEnvironment): XrayApiEndpoint = XrayApiEndpoint.LoopbackTcp(environment.allocateLoopbackApiPort())

    override suspend fun isAlive(pid: Int): Boolean = processSupervisor.isAlive(pid)

    override suspend fun kill(pid: Int, signal: Int): Boolean = processSupervisor.kill(pid, signal)

    override suspend fun readResidentMemoryMb(pid: Int): Long? = processSupervisor.readResidentMemoryMb(pid)

    override suspend fun readActiveConnectionCount(pid: Int): Int? = rootRuntime.readActiveConnectionCount(pid)

    override suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = rootRuntime.readProcessMetrics(pid)

    override suspend fun readCrashReason(): String = processSupervisor.readCrashReason()

    override suspend fun release(fastCleanup: Boolean): Boolean = if (fastCleanup && cleanup.ensureKnownStateStopped()) {
        true
    } else {
        cleanup.ensureCleanState()
    }

    // The core is not a child of this process, so there is no signal to send from a service that
    // is going away; the recorded state is what a later connection reconciles against.
    override fun requestStop() = Unit
}

internal class VpnServiceXrayRuntimeStrategy(
    private val processSupervisor: UserXrayProcessController,
    private val stateStore: ConnectionStateStore,
    private val xrayBinary: ConnectionXrayBinary,
) : XrayRuntimeStrategy {
    override val managesSystemRouting = false

    override suspend fun prepareBinary(verifyAvailable: Boolean): String? {
        if (verifyAvailable && !xrayBinary.ensureAndroidBinaryAvailable()) return null
        return xrayBinary.androidBinaryPath
    }

    override suspend fun prepareLogFile() = processSupervisor.prepareLogFile()

    // The caller still owns the descriptor, so it is handed over without suspending first.
    override suspend fun startProcess(binDir: String, vpnInterface: ParcelFileDescriptor?): Int = processSupervisor.start(
        binDir = binDir,
        tunFd = requireNotNull(vpnInterface) { "A rootless runtime cannot start without a tunnel" }.fd,
    )

    // The core is a child of this process, so a socket in the app's own namespace needs no
    // firewalling and cannot collide with another app.
    override fun nextApiEndpoint(environment: ConnectionEnvironment): XrayApiEndpoint = XrayApiEndpoint.UnixSocket(
        "$XRAY_API_SOCKET_NAME_PREFIX-${environment.processId}-${environment.elapsedRealtime()}",
    )

    override suspend fun isAlive(pid: Int): Boolean = processSupervisor.isAlive(pid)

    override suspend fun kill(pid: Int, signal: Int): Boolean = processSupervisor.kill(pid, signal)

    override suspend fun readResidentMemoryMb(pid: Int): Long? = processSupervisor.readResidentMemoryMb(pid)

    override suspend fun readActiveConnectionCount(pid: Int): Int? = withContext(Dispatchers.IO) {
        processSupervisor.readActiveConnectionCount(pid)
    }

    override suspend fun readProcessMetrics(pid: Int): ProcessMetrics = ProcessMetrics(
        residentMemoryMb = readResidentMemoryMb(pid),
        activeConnectionCount = readActiveConnectionCount(pid),
    )

    override suspend fun readCrashReason(): String = processSupervisor.readCrashReason()

    // Nothing is installed outside the process, so stopping the child and dropping the record it
    // left behind is the whole teardown.
    override suspend fun release(fastCleanup: Boolean): Boolean {
        processSupervisor.stop()
        stateStore.delete()
        return true
    }

    override fun requestStop() = processSupervisor.requestStop()
}

/**
 * Recorded in place of a physical interface by a runtime that has none, so a state file written by
 * an earlier process still identifies which runtime created it.
 */
internal const val VPN_SERVICE_INTERFACE_LABEL = "VpnService"
