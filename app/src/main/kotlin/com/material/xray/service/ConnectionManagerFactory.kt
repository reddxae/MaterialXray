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
import com.material.xray.core.xray.XrayBinary
import com.material.xray.core.xray.XrayRoutingClient
import com.material.xray.core.xray.XrayStatsClient
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SubscriptionAppRoutingRepository
import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal interface ConnectionEnvironment {
    val binDir: String
    val appUid: Int
    val processId: Int

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

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun localizedString(resourceId: Int, vararg arguments: Any): String = context.localizedString(resourceId, *arguments)
}

internal interface ConnectionRootRuntime {
    suspend fun open(): Boolean
    fun networkNamespaceName(): String
    suspend fun readActiveConnectionCount(pid: Int): Int?
}

internal class RootShellConnectionRuntime(
    private val shell: RootShell,
) : ConnectionRootRuntime {
    override suspend fun open(): Boolean = shell.open()

    override fun networkNamespaceName(): String = shell.defaultNetworkNamespace().name.lowercase()

    override suspend fun readActiveConnectionCount(pid: Int): Int? = shell
        .execute("ls -l /proc/$pid/fd 2>/dev/null | grep -c 'socket:'")
        .output
        .trim()
        .toIntOrNull()
}

internal interface ConnectionXrayBinary : XrayProcessBinary {
    fun ensureRootBinaryExtracted(): Boolean
    fun ensureAndroidBinaryAvailable(): Boolean
    fun writeConfig(configJson: String)
}

internal class XrayBinaryConnectionAdapter(
    private val binary: XrayBinary,
) : ConnectionXrayBinary {
    override val rootBinaryPath: String
        get() = binary.rootBinaryPath
    override val androidBinaryPath: String?
        get() = binary.androidBinaryPath

    override fun configPath(): String = binary.configPath()
    override fun ensureRootBinaryExtracted(): Boolean = binary.ensureRootBinaryExtracted()
    override fun ensureAndroidBinaryAvailable(): Boolean = binary.ensureAndroidBinaryAvailable()
    override fun writeConfig(configJson: String) = binary.writeConfig(configJson)
}

internal interface ConnectionCleanup {
    suspend fun ensureCleanState(fallbackTunName: String = "xray0")
    suspend fun ensureKnownStateStopped(fallbackTunName: String = "xray0"): Boolean
}

internal class CleanupManagerConnectionAdapter(
    private val cleanupManager: CleanupManager,
) : ConnectionCleanup {
    override suspend fun ensureCleanState(fallbackTunName: String) = cleanupManager.ensureCleanState(fallbackTunName)

    override suspend fun ensureKnownStateStopped(fallbackTunName: String): Boolean = cleanupManager.ensureKnownStateStopped(fallbackTunName)
}

internal data class ServerResolution(
    val server: ServerConfig,
    val attempted: Boolean,
    val selectedAddress: String?,
    val candidates: List<String>,
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
}

internal interface ConnectionRoutingClient : AutoCloseable {
    suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection?
}

internal data class ConnectionApiClients(
    val stats: ConnectionStatsClient,
    val routing: ConnectionRoutingClient,
)

internal fun interface ConnectionApiClientFactory {
    fun create(socketName: String): ConnectionApiClients
}

internal class AndroidConnectionApiClientFactory : ConnectionApiClientFactory {
    override fun create(socketName: String): ConnectionApiClients = ConnectionApiClients(
        stats = XrayStatsClientAdapter(XrayStatsClient(socketName)),
        routing = XrayRoutingClientAdapter(XrayRoutingClient(socketName)),
    )
}

private class XrayStatsClientAdapter(
    private val client: XrayStatsClient,
) : ConnectionStatsClient {
    override suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = client.queryOutboundTrafficStatsBytes()
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
    private val subscriptionAppRoutingRepository: SubscriptionAppRoutingRepository,
    private val serverRepository: ServerRepository,
    private val appInventory: AppInventory,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
) {
    internal fun create(onXrayLogReady: () -> Unit): ConnectionManager {
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
            providerRoutingSync = { subscriptionAppRoutingRepository.syncInstalledApps() },
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
            onXrayLogReady = onXrayLogReady,
        )
    }
}
