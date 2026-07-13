package com.material.xray.service

import com.material.xray.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataStatus
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ConnectionState
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    @Test
    fun `binary setup failure cleans runtime and publishes error`() = runTest {
        val harness = Harness().apply { binary.rootReady = false }

        harness.manager.connect(server(), runtimeSettings(), cleanStateFirst = false)

        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(0, harness.rootProcess.startCalls)
        assertEquals(1, harness.userProcess.stopCalls)
        assertTrue(harness.logReady)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_xray_binary_not_found)),
            harness.stateCoordinator.state.value,
        )
    }

    @Test
    fun `process readiness failure records diagnostics before cleanup`() = runTest {
        val harness = Harness().apply {
            tunGateway.configureResult = TunManager.TunSetupResult(success = false, processExited = true)
            rootProcess.crashReason = "startup failed"
        }

        harness.manager.connect(server(), runtimeSettings(), cleanStateFirst = false)

        assertEquals(1, harness.diagnostics.calls)
        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(0, harness.tunGateway.applyCalls)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_xray_crashed)),
            harness.stateCoordinator.state.value,
        )
    }

    @Test
    fun `routing failure cleans runtime and publishes error`() = runTest {
        val harness = Harness().apply {
            tunGateway.routingResult = TunManager.RoutingResult(success = false, error = "route failed")
        }

        harness.manager.connect(server(), runtimeSettings(), cleanStateFirst = false)

        assertEquals(1, harness.tunGateway.applyCalls)
        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_apply_ip_routing)),
            harness.stateCoordinator.state.value,
        )
    }

    @Test
    fun `disconnect falls back to complete cleanup when known state stop fails`() = runTest {
        val harness = Harness().apply {
            stateStore.state = XrayState(xrayPid = 42)
            cleanup.knownStateStopped = false
        }

        harness.manager.disconnect(updateState = true, fastRootCleanup = true)

        assertEquals(1, harness.cleanup.knownStateStopCalls)
        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(1, harness.userProcess.stopCalls)
        assertEquals(ConnectionState.Disconnected, harness.stateCoordinator.state.value)
    }

    private class Harness {
        val environment = FakeConnectionEnvironment()
        val rootRuntime = FakeRootRuntime()
        val binary = FakeXrayBinary()
        val tunGateway = FakeTunGateway()
        val cleanup = FakeCleanup()
        val stateStore = FakeStateStore()
        val rootProcess = FakeRootProcess()
        val userProcess = FakeUserProcess()
        val diagnostics = FakeDiagnostics()
        val stateCoordinator = ConnectionStateCoordinator()
        private val apiClients = FakeApiClients()
        var logReady = false

        val manager = ConnectionManager(
            configGenerator = ConfigGenerator(),
            stateCoordinator = stateCoordinator,
            log = LogBuffer(),
            dependencies = ConnectionManagerDependencies(
                environment = environment,
                rootRuntime = rootRuntime,
                xrayBinary = binary,
                routingData = FakeRoutingData(),
                serverResolver = FakeServerResolver(),
                tunGateway = tunGateway,
                cleanup = cleanup,
                stateStore = stateStore,
                rootProcess = rootProcess,
                userProcess = userProcess,
                diagnostics = diagnostics,
                routingPlanBuilder = EmptyRoutingPlanBuilder(),
                activeRouting = FakeActiveRoutingController(),
                apiClientFactory = ConnectionApiClientFactory { apiClients.clients },
            ),
            onXrayLogReady = { logReady = true },
        )
    }

    private class FakeConnectionEnvironment : ConnectionEnvironment {
        override val binDir = "/tmp/xray/bin"
        override val appUid = 10_123
        override val processId = 123
        private var clock = 0L

        override fun elapsedRealtime(): Long = clock++

        override fun localizedString(resourceId: Int, vararg arguments: Any): String = message(resourceId)

        fun message(resourceId: Int): String = "message:$resourceId"
    }

    private class FakeRootRuntime : ConnectionRootRuntime {
        override suspend fun open(): Boolean = true
        override fun networkNamespaceName(): String = "init"
        override suspend fun readActiveConnectionCount(pid: Int): Int = 0
    }

    private class FakeXrayBinary : ConnectionXrayBinary {
        override val rootBinaryPath = "/tmp/xray/bin/xray"
        override val androidBinaryPath = "/tmp/xray/libxray.so"
        var rootReady = true

        override fun configPath(): String = "/tmp/xray/config.json"
        override fun ensureRootBinaryExtracted(): Boolean = rootReady
        override fun ensureAndroidBinaryAvailable(): Boolean = true
        override fun writeConfig(configJson: String) = Unit
    }

    private class FakeRoutingData : ConnectionRoutingData {
        override suspend fun needsRefresh(): Boolean = false

        override suspend fun ensureReady() = GeoDataStatus(
            geoipUrl = "https://example.com/geoip.dat",
            geositeUrl = "https://example.com/geosite.dat",
            downloaded = false,
        )
    }

    private class FakeServerResolver : ConnectionServerResolver {
        override suspend fun resolve(server: ServerConfig, allowIpv6: Boolean) = ServerResolution(
            server = server,
            attempted = false,
            selectedAddress = null,
            candidates = emptyList(),
        )
    }

    private class FakeTunGateway : TunRoutingGateway {
        var configureResult = TunManager.TunSetupResult(success = true)
        var routingResult = TunManager.RoutingResult(success = true)
        var applyCalls = 0

        override suspend fun detectPhysicalRoute(tunName: String) = TunManager.PhysicalRoute(
            dev = "wlan0",
            gateway = "192.0.2.1",
            table = "main",
        )

        override suspend fun configureTun(
            tunName: String,
            addressCidr: String,
            isProcessAlive: suspend () -> Boolean,
        ): TunManager.TunSetupResult = configureResult

        override suspend fun applyRouting(
            tunName: String,
            fwmark: Int,
            routeTable: Int,
            bypassTable: Int,
            physicalRoute: TunManager.PhysicalRoute,
            bypassUids: Set<Int>,
            appTunRoutes: List<TunManager.AppTunRoute>,
            managedAppRouteCount: Int,
            routeProfileIds: Set<Int>,
        ): TunManager.RoutingResult {
            applyCalls += 1
            return routingResult
        }
    }

    private class FakeCleanup : ConnectionCleanup {
        var cleanCalls = 0
        var knownStateStopCalls = 0
        var knownStateStopped = true

        override suspend fun ensureCleanState(fallbackTunName: String) {
            cleanCalls += 1
        }

        override suspend fun ensureKnownStateStopped(fallbackTunName: String): Boolean {
            knownStateStopCalls += 1
            return knownStateStopped
        }
    }

    private class FakeStateStore : ConnectionStateStore {
        var state: XrayState? = null

        override fun read(): XrayState? = state

        override fun write(state: XrayState) {
            this.state = state
        }

        override fun delete() {
            state = null
        }
    }

    private class FakeRootProcess : RootXrayProcessController {
        var startCalls = 0
        var crashReason = "process failed"

        override suspend fun prepareLogFile() = Unit

        override suspend fun start(binDir: String): Int {
            startCalls += 1
            return 42
        }

        override suspend fun isAlive(pid: Int): Boolean = true
        override suspend fun kill(pid: Int, signal: Int): Boolean = true
        override suspend fun readResidentMemoryMb(pid: Int): Long = 1
        override suspend fun readCrashReason(lines: Int): String = crashReason
        override suspend fun ensureNativeRuntimeExemptions() = Unit
    }

    private class FakeUserProcess : UserXrayProcessController {
        var stopCalls = 0

        override fun prepareLogFile() = Unit
        override fun start(binDir: String, tunFd: Int): Int = 42
        override suspend fun isAlive(pid: Int): Boolean = true
        override suspend fun kill(pid: Int, signal: Int): Boolean = true

        override suspend fun stop() {
            stopCalls += 1
        }

        override suspend fun stopOrphan(pid: Int) = Unit
        override fun requestStop() = Unit
        override suspend fun readResidentMemoryMb(pid: Int): Long = 1
        override suspend fun readCrashReason(lines: Int): String = "process failed"
        override fun readActiveConnectionCount(pid: Int): Int = 0
    }

    private class FakeDiagnostics : ConnectionDiagnosticReporter {
        var calls = 0

        override suspend fun logNamespaceDiagnostics(stage: String, tunName: String?, xrayPid: Int?) {
            calls += 1
        }
    }

    private class EmptyRoutingPlanBuilder : RoutingPlanBuilder {
        override suspend fun build(
            baseTunName: String,
            baseRouteTable: Int,
            includeProxyRoutes: Boolean,
            includeTunRoutes: Boolean,
            defaultProxyServer: ServerConfig?,
            allowIpv6: Boolean,
        ) = AppRoutingPlan(
            directUids = emptySet(),
            proxyRoutes = emptyList(),
            tunRoutes = emptyList(),
            proxyServerIds = emptyList(),
            routeProfileIds = setOf(0),
        )
    }

    private class FakeActiveRoutingController : ActiveRoutingController {
        override suspend fun applyAppRoutingChanges(
            connectedState: ConnectionState.Connected,
            tunName: String,
            fwmark: Int,
            routeTable: Int,
        ): Boolean = false

        override suspend fun reapplyPhysicalRoutingForNetworkChange(
            connectedState: ConnectionState.Connected,
            tunName: String,
            fwmark: Int,
            routeTable: Int,
        ): PhysicalRouteUpdateResult = PhysicalRouteUpdateResult.RequiresReconnect
    }

    private class FakeApiClients {
        private val stats = object : ConnectionStatsClient {
            override suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = emptyMap()
            override fun close() = Unit
        }
        private val routing = object : ConnectionRoutingClient {
            override suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = null
            override fun close() = Unit
        }
        val clients = ConnectionApiClients(stats, routing)
    }

    private companion object {
        fun server() = ServerConfig(
            protocol = Protocol.VLESS,
            name = "Test",
            address = "192.0.2.2",
            port = 443,
            password = "test-uuid",
            extra = mapOf("encryption" to "none"),
        )

        fun runtimeSettings() = XrayRuntimeSettings(
            tunName = "xray0",
            fwmark = 255,
            routeTable = 100,
            useRootService = true,
            dnsServers = "1.1.1.1",
            domesticDnsServers = "223.5.5.5",
            logLevel = XrayLogLevel.Error,
            defaultOutbound = XrayOutbound.Proxy,
            bypassLan = true,
            allowIpv6 = false,
            routingRules = emptyList(),
        )
    }
}
