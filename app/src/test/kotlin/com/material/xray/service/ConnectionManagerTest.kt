package com.material.xray.service

import com.material.xray.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.GeoDataStatus
import com.material.xray.core.xray.TproxyManager
import com.material.xray.core.xray.TproxyRuntimeState
import com.material.xray.core.xray.TproxyTrafficGroup
import com.material.xray.core.xray.TproxyTrafficPlan
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XraySysStats
import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.ConnectionState
import com.material.xray.model.Protocol
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    @Test
    fun `binary setup failure cleans runtime and publishes error`() = runTest {
        val harness = Harness().apply { binary.rootReady = false }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(0, harness.rootProcess.startCalls)
        assertEquals(0, harness.userProcess.stopCalls)
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

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

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

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

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

        harness.manager.disconnect(updateState = true, fastCleanup = true)

        assertEquals(1, harness.cleanup.knownStateStopCalls)
        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(0, harness.userProcess.stopCalls)
        assertEquals(ConnectionState.Disconnected, harness.stateCoordinator.state.value)
        val messages = harness.log.entries.value.map { it.message }
        assertTrue(messages.any { it == "Release Xray runtime..." })
        assertTrue(messages.any { it.startsWith("Release Xray runtime took ") })
        assertTrue(messages.any { it == "Close Xray control API..." })
        assertTrue(messages.any { it.startsWith("Close Xray control API took ") })
        assertNull(harness.stateCoordinator.connectionProgress.value)
    }

    @Test
    fun `disconnect without runtime or persisted state is already clean`() = runTest {
        val harness = Harness().apply { cleanup.knownStateStopped = false }

        harness.manager.disconnect(updateState = false, fastCleanup = true)

        assertEquals(0, harness.cleanup.knownStateStopCalls)
        assertEquals(0, harness.cleanup.cleanCalls)
    }

    @Test
    fun `full connect skips duplicate cleanup after successful root disconnect`() = runTest {
        val harness = Harness()
        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)
        assertTrue(harness.manager.disconnect(updateState = true, fastCleanup = true))

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.Full)

        assertEquals(1, harness.cleanup.knownStateStopCalls)
        assertEquals(0, harness.cleanup.cleanCalls)
        assertTrue(harness.log.entries.value.any { it.message == "Previous root runtime is already clean" })
    }

    @Test
    fun `full connect consumes persisted clean proof after service recreation`() = runTest {
        val harness = Harness().apply { cleanup.knownCleanState = true }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.Full)

        assertEquals(0, harness.cleanup.cleanCalls)
        assertFalse(harness.cleanup.knownCleanState)
        assertTrue(harness.log.entries.value.any { it.message == "Previous root runtime is already clean" })
    }

    @Test
    fun `reuse reconnect consumes clean proof before a later full connect`() = runTest {
        val harness = Harness()
        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)
        assertTrue(harness.manager.disconnect(updateState = true, fastCleanup = true))
        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.Full)

        assertEquals(1, harness.cleanup.cleanCalls)
    }

    @Test
    fun `failed cleanup prevents disconnect from being published`() = runTest {
        val harness = Harness().apply {
            stateStore.state = XrayState(xrayPid = 42)
            cleanup.knownStateStopped = false
            cleanup.cleanResult = false
        }

        val disconnected = harness.manager.disconnect(updateState = true, fastCleanup = true)

        assertFalse(disconnected)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_cleanup_failed)),
            harness.stateCoordinator.state.value,
        )
        assertEquals(42, harness.stateStore.state?.xrayPid)
        assertTrue(harness.log.entries.value.any { it.message.startsWith("Release Xray runtime failed after ") })
    }

    @Test
    fun `failed initial cleanup prevents a replacement process from starting`() = runTest {
        val harness = Harness().apply { cleanup.cleanResult = false }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.Full)

        assertEquals(0, harness.rootProcess.startCalls)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_cleanup_failed)),
            harness.stateCoordinator.state.value,
        )
    }

    @Test
    fun `successful root connection persists its protected loopback API port`() = runTest {
        val harness = Harness()

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertEquals(XrayApiEndpoint.LoopbackTcp(48_123), harness.createdApiEndpoints.single())
        assertEquals(48_123, harness.stateStore.state?.xrayApiPort)
        assertEquals(listOf(48_123 to harness.environment.appUid), harness.rootRuntime.protectedApis)
    }

    @Test
    fun `TPROXY root connection creates no TUN and persists backend state`() = runTest {
        val harness = Harness()

        harness.manager.connect(
            server(),
            runtimeSettings().copy(rootConnectionBackend = RootConnectionBackend.Tproxy),
            preparation = ConnectionPreparation.ReusePreparedRuntime,
        )

        assertEquals(0, harness.tunGateway.nameDetectionCalls)
        assertEquals(0, harness.tunGateway.configureCalls)
        assertEquals(0, harness.tunGateway.applyCalls)
        assertEquals(1, harness.tproxyGateway.guardCalls)
        assertEquals(1, harness.tproxyGateway.activateCalls)
        assertEquals(RootConnectionBackend.Tproxy, harness.stateStore.state?.rootConnectionBackend)
        assertEquals("TPROXY", harness.stateStore.state?.tunName)
        val config = Json.parseToJsonElement(requireNotNull(harness.binary.configJson)).jsonObject
        assertTrue(config.getValue("inbounds").jsonArray.none { it.jsonObject["protocol"]?.jsonPrimitive?.content == "tun" })
    }

    @Test
    fun `backend switch preserves guard until replacement runtime is ready`() = runTest {
        val harness = Harness()
        val tproxySettings = runtimeSettings().copy(rootConnectionBackend = RootConnectionBackend.Tproxy)
        harness.manager.connect(server(), tproxySettings, preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertTrue(harness.manager.prepareSeamlessReconnect())
        assertTrue(harness.manager.hasTransitionGuard)
        assertTrue(
            harness.manager.disconnect(
                updateState = false,
                fastCleanup = true,
                preserveTproxyGuard = true,
            ),
        )
        assertTrue(harness.cleanup.lastPreserveTproxyGuard)

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertFalse(harness.manager.hasTransitionGuard)
        assertEquals(2, harness.tproxyGateway.removeGuardCalls)
    }

    @Test
    fun `failed seamless replacement keeps guard for retry`() = runTest {
        val harness = Harness()
        val settings = runtimeSettings().copy(rootConnectionBackend = RootConnectionBackend.Tproxy)
        harness.manager.connect(server(), settings, preparation = ConnectionPreparation.ReusePreparedRuntime)
        assertTrue(harness.manager.prepareSeamlessReconnect())
        assertTrue(
            harness.manager.disconnect(
                updateState = false,
                fastCleanup = true,
                preserveTproxyGuard = true,
            ),
        )
        harness.tproxyGateway.activationResult = TunManager.RoutingResult(
            success = false,
            error = "iptables: Resource temporarily unavailable",
        )

        harness.manager.connect(server(), settings, preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertTrue(harness.manager.hasTransitionGuard)
        assertTrue(harness.cleanup.lastPreserveTproxyGuard)
        harness.manager.clearFailedTransitionGuard()
        assertFalse(harness.manager.hasTransitionGuard)
    }

    @Test
    fun `raw connection propagates bootstrap hosts into proxied default DNS`() = runTest {
        val rawServer = server().copy(
            protocol = Protocol.RAW,
            address = "proxy.example",
            rawConfigJson = """
                {
                  "outbounds": [
                    {"tag":"proxy","protocol":"vless","settings":{}}
                  ]
                }
            """.trimIndent(),
        )
        val resolvedServer = rawServer.copy(
            bootstrapDnsHosts = mapOf("proxy.example" to listOf("192.0.2.20")),
        )
        val harness = Harness().apply {
            serverResolver.result = ServerResolution(
                server = resolvedServer,
                attempted = true,
                selectedAddress = "192.0.2.20",
                candidates = listOf("192.0.2.20"),
            )
        }

        harness.manager.connect(rawServer, runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        val config = Json.parseToJsonElement(requireNotNull(harness.binary.configJson)).jsonObject
        val hosts = config.getValue("dns").jsonObject.getValue("hosts").jsonObject
        assertEquals(
            listOf("192.0.2.20"),
            hosts.getValue("proxy.example").jsonArray.map { it.jsonPrimitive.content },
        )
        val defaultDnsRule = config.getValue("routing").jsonObject.getValue("rules").jsonArray.first {
            it.jsonObject["inboundTag"]?.jsonArray?.singleOrNull()?.jsonPrimitive?.content == "default-dns"
        }
        assertEquals("proxy", defaultDnsRule.jsonObject.getValue("outboundTag").jsonPrimitive.content)
        assertEquals(listOf(rawServer), harness.serverResolver.servers)
    }

    @Test
    fun `server resolution retries without rerunning earlier setup`() = runTest {
        val unresolved = ServerResolution(
            server = server(),
            attempted = true,
            selectedAddress = null,
            candidates = emptyList(),
            unresolvedHosts = listOf(server().address),
        )
        val resolved = unresolved.copy(
            server = server().copy(address = "192.0.2.20"),
            selectedAddress = "192.0.2.20",
            candidates = listOf("192.0.2.20"),
            unresolvedHosts = emptyList(),
        )
        val harness = Harness().apply {
            serverResolver.results += listOf(unresolved, unresolved, resolved)
        }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertTrue(harness.stateCoordinator.state.value is ConnectionState.Connected)
        assertEquals(3, harness.serverResolver.servers.size)
        assertEquals(1, harness.rootRuntime.openCalls)
        assertEquals(1, harness.rootProcess.startCalls)
    }

    @Test
    fun `physical route detection retries without rerunning earlier setup`() = runTest {
        val route = TunManager.PhysicalRoute(dev = "wlan0", gateway = "192.0.2.1", table = "main")
        val harness = Harness().apply {
            tunGateway.physicalRoutes += listOf(null, null, route)
        }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertTrue(harness.stateCoordinator.state.value is ConnectionState.Connected)
        assertEquals(3, harness.tunGateway.detectedRouteTunNames.size)
        assertEquals(1, harness.rootRuntime.openCalls)
        assertEquals(1, harness.serverResolver.servers.size)
        assertEquals(1, harness.rootProcess.startCalls)
    }

    @Test
    fun `connection is not published before Xray API readiness`() = runTest {
        val harness = Harness().apply { apiClients.sysStats = null }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_xray_api_not_ready)),
            harness.stateCoordinator.state.value,
        )
        assertEquals(1, harness.cleanup.cleanCalls)
    }

    @Test
    fun `connection waits through transient Xray API startup failures`() = runTest {
        val harness = Harness().apply { apiClients.unavailableSysStatsQueries = 3 }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertTrue(harness.stateCoordinator.state.value is ConnectionState.Connected)
        assertEquals(4, harness.apiClients.sysStatsQueries)
    }

    @Test
    fun `connection cancellation cleans partial runtime without publishing an error`() = runTest {
        val harness = Harness()
        val queryStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        harness.apiClients.queryStarted = queryStarted
        harness.apiClients.releaseQuery = CompletableDeferred()
        harness.cleanup.cleanStarted = cleanupStarted
        harness.cleanup.releaseClean = releaseCleanup

        val connection = async {
            harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)
        }
        queryStarted.await()
        connection.cancel()
        cleanupStarted.await()
        releaseCleanup.complete(Unit)
        connection.join()

        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(1, harness.apiClients.statsCloseCalls)
        assertEquals(ConnectionState.Disconnected, harness.stateCoordinator.state.value)
        assertNull(harness.stateCoordinator.connectionProgress.value)
    }

    @Test
    fun `restored root connection recreates stats and routing clients`() = runTest {
        val harness = Harness()
        val selection = ActiveBalancerSelection(outboundTag = "proxy-2", latencyMs = 45)
        harness.apiClients.trafficStats = mapOf("outbound>>>proxy>>>traffic>>>uplink" to 1024L)
        harness.apiClients.balancerSelection = selection
        harness.stateStore.state = XrayState(xrayPid = 42, xrayApiPort = 49_321)

        val restored = harness.manager.restoreRootApiClients()

        assertTrue(restored)
        assertEquals(listOf(XrayApiEndpoint.LoopbackTcp(49_321)), harness.createdApiEndpoints)
        assertEquals(listOf(49_321 to harness.environment.appUid), harness.rootRuntime.protectedApis)
        assertEquals(harness.apiClients.trafficStats, harness.manager.readOutboundTrafficStatsBytes())
        assertEquals(harness.apiClients.sysStats, harness.manager.readXraySysStats())
        assertEquals(selection, harness.manager.readBalancerSelection("primary"))
    }

    @Test
    fun `service destruction defers API client close until active query completes`() = runTest {
        val harness = Harness()
        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        harness.apiClients.queryStarted = queryStarted
        harness.apiClients.releaseQuery = releaseQuery

        val query = async { harness.manager.readXraySysStats() }
        queryStarted.await()
        harness.manager.prepareForServiceDestruction()

        assertEquals(0, harness.apiClients.statsCloseCalls)
        releaseQuery.complete(Unit)
        query.await()
        assertEquals(1, harness.apiClients.statsCloseCalls)
    }

    @Test
    fun `service destruction closes API clients created by a queued replacement`() = runTest {
        val harness = Harness()
        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)
        val queryStarted = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        harness.apiClients.queryStarted = queryStarted
        harness.apiClients.releaseQuery = releaseQuery

        val query = async { harness.manager.readXraySysStats() }
        queryStarted.await()
        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            harness.manager.restoreRootApiClients()
        }
        harness.manager.prepareForServiceDestruction()

        releaseQuery.complete(Unit)
        query.await()
        assertTrue(replacement.await())
        assertEquals(2, harness.apiClients.statsCloseCalls)
    }

    @Test
    fun `root state restores missing core API port from config`() = runTest {
        val harness = Harness().apply {
            stateStore.state = XrayState(xrayPid = 42)
            binary.configJson = """{"api":{"listen":"127.0.0.1:49322"}}"""
        }

        val restored = harness.manager.restoreRootApiClients()

        assertTrue(restored)
        assertEquals(listOf(XrayApiEndpoint.LoopbackTcp(49_322)), harness.createdApiEndpoints)
    }

    @Test
    fun `legacy Unix API requests core restart instead of creating unusable clients`() = runTest {
        val harness = Harness().apply {
            stateStore.state = XrayState(xrayPid = 42)
            binary.configJson = """{"api":{"listen":"@material-xray-api-legacy"}}"""
        }

        assertFalse(harness.manager.restoreRootApiClients())
        assertTrue(harness.createdApiEndpoints.isEmpty())
    }

    @Test
    fun `root restore leaves the firewall untouched without a restorable state`() = runTest {
        val missingState = Harness().apply {
            binary.configJson = """{"api":{"listen":"127.0.0.1:49322"}}"""
        }
        val rootlessState = Harness().apply {
            stateStore.state = XrayState(xrayPid = 42, xrayApiPort = 49_321, physicalInterface = VPN_SERVICE_INTERFACE_LABEL)
        }

        assertFalse(missingState.manager.restoreRootApiClients())
        assertFalse(rootlessState.manager.restoreRootApiClients())

        assertTrue(missingState.rootRuntime.protectedApis.isEmpty())
        assertTrue(rootlessState.rootRuntime.protectedApis.isEmpty())
    }

    @Test
    fun `root connection fails closed when API firewall cannot be installed`() = runTest {
        val harness = Harness().apply { rootRuntime.apiProtectionReady = false }

        harness.manager.connect(server(), runtimeSettings(), preparation = ConnectionPreparation.ReusePreparedRuntime)

        assertEquals(0, harness.rootProcess.startCalls)
        assertEquals(1, harness.cleanup.cleanCalls)
        assertEquals(
            ConnectionState.Error(harness.environment.message(R.string.connection_error_secure_xray_api)),
            harness.stateCoordinator.state.value,
        )
    }

    @Test
    fun `root connection resolves an empty TUN name to an available wlan name`() = runTest {
        val harness = Harness().apply { tunGateway.availableWlanName = "wlan2" }

        harness.manager.connect(
            server(),
            runtimeSettings().copy(tunName = ""),
            preparation = ConnectionPreparation.ReusePreparedRuntime,
        )

        assertEquals(1, harness.tunGateway.nameDetectionCalls)
        assertEquals(listOf("wlan2"), harness.tunGateway.detectedRouteTunNames)
        assertEquals("wlan2", harness.stateStore.state?.tunName)
        assertEquals("wlan2", (harness.stateCoordinator.state.value as ConnectionState.Connected).tunName)
    }

    @Test
    fun `root connection preserves a custom TUN name`() = runTest {
        val harness = Harness()

        harness.manager.connect(
            server(),
            runtimeSettings().copy(tunName = "custom0"),
            preparation = ConnectionPreparation.ReusePreparedRuntime,
        )

        assertEquals(0, harness.tunGateway.nameDetectionCalls)
        assertEquals("custom0", harness.stateStore.state?.tunName)
    }

    @Test
    fun `root connection applies configured IPv6 policy`() = runTest {
        val harness = Harness()

        harness.manager.connect(
            server(),
            runtimeSettings().copy(allowIpv6 = true),
            preparation = ConnectionPreparation.ReusePreparedRuntime,
        )

        assertTrue(harness.tunGateway.lastAllowIpv6)
        assertTrue(TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR in harness.tunGateway.configuredIpv6Addresses)
    }

    @Test
    fun `active routing uses resolved TUN name instead of empty setting`() = runTest {
        val harness = Harness()
        val connectedState = ConnectionState.Connected(
            serverName = "Test",
            corePid = 42,
            tunName = "wlan2",
            physicalInterface = "wlan0",
        )

        harness.manager.applyAppRoutingChanges(
            connectedState,
            runtimeSettings().copy(tunName = ""),
        )

        assertEquals("wlan2", harness.activeRouting.lastTunName)
    }

    @Test
    fun `rootless teardown stops the child instead of reclaiming root state`() = runTest {
        val harness = Harness()
        harness.stateStore.state = XrayState(xrayPid = 42, physicalInterface = VPN_SERVICE_INTERFACE_LABEL)

        harness.manager.disconnect(updateState = true, fastCleanup = true)

        assertEquals(1, harness.userProcess.stopCalls)
        assertEquals(0, harness.cleanup.knownStateStopCalls)
        assertEquals(0, harness.cleanup.cleanCalls)
        assertNull(harness.stateStore.state)
        assertEquals(ConnectionState.Disconnected, harness.stateCoordinator.state.value)
    }

    @Test
    fun `a rootless connection without a tunnel is rejected without reclaiming root state`() = runTest {
        val harness = Harness()
        harness.stateStore.state = XrayState(xrayPid = 42, physicalInterface = VPN_SERVICE_INTERFACE_LABEL)

        harness.manager.connect(
            server(),
            runtimeSettings().copy(useRootService = false, tunName = "tun0"),
            vpnInterface = null,
        )

        assertEquals(1, harness.userProcess.stopCalls)
        assertEquals(0, harness.cleanup.cleanCalls)
        assertEquals(0, harness.cleanup.knownStateStopCalls)
        assertNull(harness.stateStore.state)
        assertEquals(
            ConnectionState.Error(
                harness.environment.message(R.string.connection_error_vpn_permission_required),
                retryable = false,
            ),
            harness.stateCoordinator.state.value,
        )
    }

    private class Harness {
        val environment = FakeConnectionEnvironment()
        val rootRuntime = FakeRootRuntime()
        val binary = FakeXrayBinary()
        val tunGateway = FakeTunGateway()
        val tproxyGateway = FakeTproxyGateway(environment.appUid)
        val cleanup = FakeCleanup()
        val stateStore = FakeStateStore()
        val rootProcess = FakeRootProcess()
        val userProcess = FakeUserProcess()
        val diagnostics = FakeDiagnostics()
        val activeRouting = FakeActiveRoutingController()
        val stateCoordinator = ConnectionStateCoordinator()
        val log = LogBuffer()
        val apiClients = FakeApiClients()
        val serverResolver = FakeServerResolver()
        val createdApiEndpoints = mutableListOf<XrayApiEndpoint>()
        val manager = ConnectionManager(
            configGenerator = ConfigGenerator(),
            stateCoordinator = stateCoordinator,
            log = log,
            dependencies = ConnectionManagerDependencies(
                environment = environment,
                rootRuntime = rootRuntime,
                xrayBinary = binary,
                routingData = FakeRoutingData(),
                serverResolver = serverResolver,
                tunGateway = tunGateway,
                tproxyGateway = tproxyGateway,
                cleanup = cleanup,
                stateStore = stateStore,
                rootProcess = rootProcess,
                userProcess = userProcess,
                diagnostics = diagnostics,
                routingPlanBuilder = EmptyRoutingPlanBuilder(),
                activeRouting = activeRouting,
                apiClientFactory = ConnectionApiClientFactory { endpoint ->
                    createdApiEndpoints += endpoint
                    apiClients.clients
                },
            ),
        )
    }

    private class FakeConnectionEnvironment : ConnectionEnvironment {
        override val binDir = "/tmp/xray/bin"
        override val appUid = 10_123
        override val processId = 123
        override fun allocateLoopbackApiPort(): Int = 48_123
        private var clock = 0L

        override fun elapsedRealtime(): Long = clock.also { clock += 250L }

        override fun localizedString(resourceId: Int, vararg arguments: Any): String = message(resourceId)

        fun message(resourceId: Int): String = "message:$resourceId"
    }

    private class FakeRootRuntime : ConnectionRootRuntime {
        var apiProtectionReady = true
        var openCalls = 0
        val protectedApis = mutableListOf<Pair<Int, Int>>()

        override suspend fun open(): Boolean {
            openCalls += 1
            return true
        }
        override fun networkNamespaceName(): String = "init"
        override suspend fun protectLoopbackApi(port: Int, appUid: Int): Boolean {
            protectedApis += port to appUid
            return apiProtectionReady
        }
        override suspend fun readActiveConnectionCount(pid: Int): Int = 0
        override suspend fun readProcessMetrics(pid: Int): ProcessMetrics = ProcessMetrics(1, 0)
    }

    private class FakeXrayBinary : ConnectionXrayBinary {
        override val rootBinaryPath = "/tmp/xray/bin/xray"
        override val androidBinaryPath = "/tmp/xray/libxray.so"
        var rootReady = true
        var configJson: String? = null

        override fun configPath(): String = "/tmp/xray/config.json"
        override suspend fun ensureRootBinaryExtracted(): Boolean = rootReady
        override suspend fun ensureAndroidBinaryAvailable(): Boolean = true
        override suspend fun readConfig(): String? = configJson
        override suspend fun writeConfig(configJson: String) {
            this.configJson = configJson
        }
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
        var result: ServerResolution? = null
        val results = mutableListOf<ServerResolution>()
        val servers = mutableListOf<ServerConfig>()

        override suspend fun resolve(server: ServerConfig, allowIpv6: Boolean): ServerResolution {
            servers += server
            if (results.isNotEmpty()) return results.removeAt(0)
            return result ?: ServerResolution(
                server = server,
                attempted = false,
                selectedAddress = null,
                candidates = emptyList(),
            )
        }
    }

    private class FakeTunGateway : TunRoutingGateway {
        var configureResult = TunManager.TunSetupResult(success = true)
        var routingResult = TunManager.RoutingResult(success = true)
        var applyCalls = 0
        var availableWlanName: String? = "wlan0"
        var nameDetectionCalls = 0
        var lastAllowIpv6 = false
        var configureCalls = 0
        val configuredIpv6Addresses = mutableListOf<String>()
        val detectedRouteTunNames = mutableListOf<String>()
        val physicalRoutes = mutableListOf<TunManager.PhysicalRoute?>()

        override suspend fun findAvailableWlanName(): String? {
            nameDetectionCalls += 1
            return availableWlanName
        }

        override suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? {
            detectedRouteTunNames += tunName
            if (physicalRoutes.isNotEmpty()) return physicalRoutes.removeAt(0)
            return TunManager.PhysicalRoute(
                dev = "wlan0",
                gateway = "192.0.2.1",
                table = "main",
            )
        }

        override suspend fun configureTun(
            tunName: String,
            addressCidr: String,
            ipv6AddressCidr: String?,
            isProcessAlive: suspend () -> Boolean,
        ): TunManager.TunSetupResult {
            configureCalls += 1
            ipv6AddressCidr?.let(configuredIpv6Addresses::add)
            return configureResult
        }

        override suspend fun applyRouting(
            tunName: String,
            fwmark: Int,
            routeTable: Int,
            bypassTable: Int,
            physicalRoute: TunManager.PhysicalRoute,
            allowIpv6: Boolean,
            bypassUids: Set<Int>,
            appTunRoutes: List<TunManager.AppTunRoute>,
            managedAppRouteCount: Int,
            routeProfileIds: Set<Int>,
        ): TunManager.RoutingResult {
            applyCalls += 1
            lastAllowIpv6 = allowIpv6
            return routingResult
        }

        override suspend fun replacePhysicalBypassRoute(
            bypassTable: Int,
            physicalRoute: TunManager.PhysicalRoute,
        ): TunManager.RoutingResult = routingResult
    }

    private class FakeTproxyGateway(private val appUid: Int) : TproxyRoutingGateway {
        var guardCalls = 0
        var activateCalls = 0
        var removeGuardCalls = 0
        var activationResult = TunManager.RoutingResult(success = true)

        override fun createPlan(
            appRoutingPlan: AppRoutingPlan,
            routeTable: Int,
            outboundMark: Int,
            allowIpv6: Boolean,
            existingState: TproxyRuntimeState?,
        ): TproxyTrafficPlan {
            val state = existingState ?: TproxyManager.createRuntimeState(
                routeTable = routeTable + 200,
                groups = listOf(Long.MAX_VALUE to "tproxy-in-default"),
                ports = listOf(48_321),
                allowIpv6 = allowIpv6,
            )
            return TproxyTrafficPlan(
                runtimeState = state,
                groups = listOf(TproxyTrafficGroup(state.groups.single(), emptySet(), isBase = true)),
                bypassUids = appRoutingPlan.directUids + appUid,
                routeProfileIds = appRoutingPlan.routeProfileIds,
                outboundMark = outboundMark,
            )
        }

        override suspend fun installGuard(plan: TproxyTrafficPlan): TunManager.RoutingResult {
            guardCalls += 1
            return TunManager.RoutingResult(success = true)
        }
        override suspend fun activate(plan: TproxyTrafficPlan): TunManager.RoutingResult {
            activateCalls += 1
            return activationResult
        }
        override suspend fun update(plan: TproxyTrafficPlan, currentSlot: String) = TunManager.RoutingResult(success = true)
        override suspend fun verify(state: TproxyRuntimeState): Boolean = true
        override suspend fun removeGuard(): Boolean {
            removeGuardCalls += 1
            return true
        }
        override suspend fun hasGuard(): Boolean = true
    }

    private class FakeCleanup : ConnectionCleanup {
        var cleanCalls = 0
        var knownStateStopCalls = 0
        var knownStateStopped = true
        var cleanResult = true
        var cleanStarted: CompletableDeferred<Unit>? = null
        var releaseClean: CompletableDeferred<Unit>? = null
        var lastPreserveTproxyGuard = false
        var knownCleanState = false

        override suspend fun ensureCleanState(fallbackTunName: String, preserveTproxyGuard: Boolean): Boolean {
            cleanCalls += 1
            lastPreserveTproxyGuard = preserveTproxyGuard
            cleanStarted?.complete(Unit)
            releaseClean?.await()
            return cleanResult
        }

        override suspend fun ensureKnownStateStopped(fallbackTunName: String, preserveTproxyGuard: Boolean): Boolean {
            knownStateStopCalls += 1
            lastPreserveTproxyGuard = preserveTproxyGuard
            return knownStateStopped
        }

        override fun recordKnownCleanState(): Boolean {
            knownCleanState = true
            return true
        }

        override fun consumeKnownCleanState(): Boolean = knownCleanState.also { knownCleanState = false }
    }

    private class FakeStateStore : ConnectionStateStore {
        var state: XrayState? = null

        override suspend fun read(): XrayState? = state

        override suspend fun write(state: XrayState) {
            this.state = state
        }

        override suspend fun delete() {
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

        override suspend fun prepareLogFile() = Unit
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
        var lastTunName: String? = null

        override suspend fun applyAppRoutingChanges(
            connectedState: ConnectionState.Connected,
            tunName: String,
            fwmark: Int,
            routeTable: Int,
            allowIpv6: Boolean,
        ): Boolean {
            lastTunName = tunName
            return false
        }

        override suspend fun updatePhysicalBypassRoute(
            connectedState: ConnectionState.Connected,
            physicalRoute: TunManager.PhysicalRoute,
            tunName: String,
            fwmark: Int,
            routeTable: Int,
        ): PhysicalRouteUpdateResult = PhysicalRouteUpdateResult.Applied(physicalRoute)
    }

    private class FakeApiClients {
        var trafficStats: Map<String, Long> = emptyMap()
        var balancerSelection: ActiveBalancerSelection? = null
        var sysStats: XraySysStats? = XraySysStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        var unavailableSysStatsQueries = 0
        var sysStatsQueries = 0
        var queryStarted: CompletableDeferred<Unit>? = null
        var releaseQuery: CompletableDeferred<Unit>? = null
        var statsCloseCalls = 0

        private val stats = object : ConnectionStatsClient {
            override suspend fun queryOutboundTrafficStatsBytes(): Map<String, Long> = trafficStats
            override suspend fun getSysStats(): XraySysStats? {
                sysStatsQueries++
                queryStarted?.complete(Unit)
                releaseQuery?.await()
                if (unavailableSysStatsQueries > 0) {
                    unavailableSysStatsQueries--
                    return null
                }
                return sysStats
            }
            override fun close() {
                statsCloseCalls++
            }
        }
        private val routing = object : ConnectionRoutingClient {
            override suspend fun queryBalancerSelection(balancerTag: String): ActiveBalancerSelection? = balancerSelection
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
            rootConnectionBackend = RootConnectionBackend.Tun,
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
