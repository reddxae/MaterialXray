package com.material.xray.service

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import com.material.xray.core.app.AppInventory
import com.material.xray.core.locale.localizedString
import com.material.xray.core.root.RootShell
import com.material.xray.core.xray.CleanupManager
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataManager
import com.material.xray.core.xray.GeoDataStatus
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.StateFile
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XRAY_API_LOOPBACK_ADDRESS
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayApiFirewall
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayRoutingClient
import com.material.xray.core.xray.XrayStatsClient
import com.material.xray.core.xray.XraySysStats
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.ServerSocket
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface ConnectionEnvironment {
    val binDir: String
    val appUid: Int
    val processId: Int

    fun allocateLoopbackApiPort(): Int
    fun elapsedRealtime(): Long
    fun localizedString(@StringRes resourceId: Int, vararg arguments: Any): String
}

internal class AndroidConnectionEnvironment(
    private val context: Context,
) : ConnectionEnvironment {
    override val binDir: String
        get() = context.filesDir.resolve("bin").absolutePath
    override val appUid: Int
        get() = context.applicationInfo.uid
    override val processId: Int
        get() = android.os.Process.myPid()

    override fun allocateLoopbackApiPort(): Int = ServerSocket(
        0,
        1,
        InetAddress.getByName(XRAY_API_LOOPBACK_ADDRESS),
    ).use { it.localPort }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun localizedString(resourceId: Int, vararg arguments: Any): String = context.localizedString(resourceId, *arguments)
}

internal interface ConnectionRootRuntime {
    suspend fun open(): Boolean
    fun networkNamespaceName(): String
    suspend fun protectLoopbackApi(port: Int, appUid: Int): Boolean
    suspend fun readActiveConnectionCount(pid: Int): Int?
    suspend fun readProcessMetrics(pid: Int): ProcessMetrics?
}

internal class RootShellConnectionRuntime(
    private val shell: RootShell,
) : ConnectionRootRuntime {
    private val apiFirewall = XrayApiFirewall(shell)

    override suspend fun open(): Boolean = shell.open(RootShell.NetworkNamespace.INIT)

    override fun networkNamespaceName(): String = shell.defaultNetworkNamespace().name.lowercase()

    override suspend fun protectLoopbackApi(port: Int, appUid: Int): Boolean = apiFirewall.apply(port, appUid)

    override suspend fun readActiveConnectionCount(pid: Int): Int? = shell
        .execute("ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'")
        .output
        .trim()
        .toIntOrNull()

    override suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = shell.execute(
        "rss=\$(awk '/^VmRSS:/ { print \$2 }' /proc/$pid/status 2>/dev/null); " +
            "sockets=\$(ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'); " +
            "printf 'rss_kb=%s\\nsockets=%s\\n' \"\$rss\" \"\$sockets\"",
    ).takeIf { it.isSuccess }?.output?.let(::parseProcessMetrics)
}

internal data class ProcessMetrics(
    val residentMemoryMb: Long?,
    val activeConnectionCount: Int?,
)

internal fun parseProcessMetrics(output: String): ProcessMetrics? {
    val values = output.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }.toMap()
    val rssKb = values["rss_kb"]?.toLongOrNull()
    val sockets = values["sockets"]?.toIntOrNull()
    if (rssKb == null && sockets == null) return null
    return ProcessMetrics(
        residentMemoryMb = rssKb?.let { (it + KILOBYTES_PER_MEBIBYTE - 1) / KILOBYTES_PER_MEBIBYTE },
        activeConnectionCount = sockets,
    )
}

private const val KILOBYTES_PER_MEBIBYTE = 1024L

internal interface ConnectionXrayBinary : XrayProcessBinary {
    suspend fun ensureRootBinaryExtracted(): Boolean
    suspend fun ensureAndroidBinaryAvailable(): Boolean
    suspend fun readConfig(): String?
    suspend fun writeConfig(configJson: String)
}

// Extracting the bundled core, and reading or writing config.json, are file operations that must
// not run on whichever thread the caller happens to be on; the service issues connection commands
// on the main thread.
internal class XrayBinaryConnectionAdapter(
    private val binary: XrayBinary,
) : ConnectionXrayBinary {
    override val rootBinaryPath: String
        get() = binary.rootBinaryPath
    override val androidBinaryPath: String?
        get() = binary.androidBinaryPath

    override fun configPath(): String = binary.configPath()

    override suspend fun ensureRootBinaryExtracted(): Boolean = withContext(Dispatchers.IO) {
        binary.ensureRootBinaryExtracted()
    }

    override suspend fun ensureAndroidBinaryAvailable(): Boolean = withContext(Dispatchers.IO) {
        binary.ensureAndroidBinaryAvailable()
    }

    override suspend fun readConfig(): String? = withContext(Dispatchers.IO) { binary.readConfig() }

    override suspend fun writeConfig(configJson: String) {
        withContext(Dispatchers.IO) { binary.writeConfig(configJson) }
    }
}

internal interface ConnectionCleanup {
    suspend fun ensureCleanState(fallbackTunName: String = "xray0"): Boolean
    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0"): Boolean
}

internal class CleanupManagerConnectionAdapter(
    private val cleanupManager: CleanupManager,
) : ConnectionCleanup {
    override suspend fun ensureCleanState(fallbackTunName: String): Boolean = cleanupManager.ensureCleanState(fallbackTunName)

    override suspend fun ensureKnownStateStopped(fallbackTunName: String): Boolean = cleanupManager.ensureKnownStateStopped(fallbackTunName)
}

internal data class ServerResolution(
    val server: ServerConfig,
    val attempted: Boolean,
    val selectedAddress: String?,
    val candidates: List<String>,
    val unresolvedHosts: List<String> = emptyList(),
)

internal interface ConnectionServerResolver {
    suspend fun resolve(server: ServerConfig, allowIpv6: Boolean): ServerResolution
}

internal class ServerAddressConnectionResolver(
    private val resolver: ServerAddressResolver,
) : ConnectionServerResolver {
    override suspend fun resolve(server: ServerConfig, allowIpv6: Boolean): ServerResolution = resolver
        .resolve(server, allowIpv6)
        .let { result ->
            ServerResolution(
                server = result.server,
                attempted = result.attempted,
                selectedAddress = result.selectedAddress,
                candidates = result.candidates,
                unresolvedHosts = result.unresolvedHosts,
            )
        }
}

internal interface ConnectionRoutingData {
    suspend fun needsRefresh(): Boolean
    suspend fun ensureReady(): GeoDataStatus
}

internal class GeoDataConnectionRoutingData(
    private val geoDataManager: GeoDataManager,
) : ConnectionRoutingData {
    override suspend fun needsRefresh(): Boolean = geoDataManager.needsRefresh()
    override suspend fun ensureReady(): GeoDataStatus = geoDataManager.ensureReady()
}

internal interface ConnectionStatsClient : AutoCloseable {
    suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long>
    suspend fun getSysStats(): XraySysStats?
}

internal interface ConnectionRoutingClient : AutoCloseable {
    suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection?
}

internal data class ConnectionApiClients(
    val stats: ConnectionStatsClient,
    val routing: ConnectionRoutingClient,
)

internal fun interface ConnectionApiClientFactory {
    fun create(endpoint: XrayApiEndpoint): ConnectionApiClients
}

internal class AndroidConnectionApiClientFactory : ConnectionApiClientFactory {
    override fun create(endpoint: XrayApiEndpoint): ConnectionApiClients = ConnectionApiClients(
        stats = XrayStatsClientAdapter(XrayStatsClient(endpoint)),
        routing = XrayRoutingClientAdapter(XrayRoutingClient(endpoint)),
    )
}

private class XrayStatsClientAdapter(
    private val client: XrayStatsClient,
) : ConnectionStatsClient {
    override suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = client.queryOutboundTrafficStatsBytes()
    override suspend fun getSysStats(): XraySysStats? = client.getSysStats()
    override fun close() = client.close()
}

private class XrayRoutingClientAdapter(
    private val client: XrayRoutingClient,
) : ConnectionRoutingClient {
    override suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = client.queryBalancerSelection(balancerTag)
    override fun close() = client.close()
}

internal data class ConnectionManagerDependencies(
    val environment: ConnectionEnvironment,
    val rootRuntime: ConnectionRootRuntime,
    val xrayBinary: ConnectionXrayBinary,
    val routingData: ConnectionRoutingData,
    val serverResolver: ConnectionServerResolver,
    val tunGateway: TunRoutingGateway,
    val cleanup: ConnectionCleanup,
    val stateStore: ConnectionStateStore,
    val rootProcess: RootXrayProcessController,
    val userProcess: UserXrayProcessController,
    val diagnostics: ConnectionDiagnosticReporter,
    val routingPlanBuilder: RoutingPlanBuilder,
    val activeRouting: ActiveRoutingController,
    val apiClientFactory: ConnectionApiClientFactory,
)

class ConnectionManagerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: RootShell,
    private val geoDataManager: GeoDataManager,
    private val appBypassDao: AppBypassDao,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventory,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
) {
    internal fun create(): ConnectionManager {
        val environment = AndroidConnectionEnvironment(context)
        val xrayBinary = XrayBinaryConnectionAdapter(XrayBinary(context))
        val runtimeEnvironment = AndroidXrayRuntimeEnvironment(context)
        val tunGateway = TunManagerRoutingGateway(TunManager(shell))
        val stateStore = StateFileRoutingStateStore(StateFile(context))
        val serverAddressResolver = ServerAddressResolver(context)
        val rootProcess = XrayProcessSupervisor(
            environment = runtimeEnvironment,
            commandRunner = RootShellCommandRunner(shell),
            xrayBinary = xrayBinary,
            certificateBundle = AndroidRootCertificateBundle(),
            log = log,
        )
        val userProcess = UserXrayProcessSupervisor(
            environment = runtimeEnvironment,
            xrayBinary = xrayBinary,
        )
        val routingPlanBuilder = AppRoutingPlanner(
            appBypassDao = appBypassDao,
            serverRepository = serverRepository,
            appInventory = appInventory,
            serverAddressResolver = serverAddressResolver,
            log = log,
        )
        val dependencies = ConnectionManagerDependencies(
            environment = environment,
            rootRuntime = RootShellConnectionRuntime(shell),
            xrayBinary = xrayBinary,
            routingData = GeoDataConnectionRoutingData(geoDataManager),
            serverResolver = ServerAddressConnectionResolver(serverAddressResolver),
            tunGateway = tunGateway,
            cleanup = CleanupManagerConnectionAdapter(CleanupManager(context, shell)),
            stateStore = stateStore,
            rootProcess = rootProcess,
            userProcess = userProcess,
            diagnostics = ConnectionDiagnostics(RootShellDiagnosticCommandRunner(shell), log),
            routingPlanBuilder = routingPlanBuilder,
            activeRouting = ActiveRoutingUpdater(
                appUidProvider = { environment.appUid },
                tunGateway = tunGateway,
                stateStore = stateStore,
                routingPlanBuilder = routingPlanBuilder,
                processProbe = rootProcess,
                log = log,
                elapsedRealtime = environment::elapsedRealtime,
            ),
            apiClientFactory = AndroidConnectionApiClientFactory(),
        )
        return ConnectionManager(
            configGenerator = ConfigGenerator(),
            stateCoordinator = stateCoordinator,
            log = log,
            dependencies = dependencies,
        )
    }
}
