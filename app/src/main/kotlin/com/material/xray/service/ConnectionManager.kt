package com.material.xray.service

import android.os.ParcelFileDescriptor
import com.material.xray.R
import com.material.xray.core.xray.ConfigGenerator
import com.material.xray.core.xray.TproxyTrafficPlan
import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayApiEndpoint
import com.material.xray.core.xray.XrayInbound
import com.material.xray.core.xray.XrayState
import com.material.xray.core.xray.XraySysStats
import com.material.xray.core.xray.parseXrayApiEndpoint
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.ServerConfig
import com.material.xray.model.XrayRuntimeSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

@Suppress("LargeClass")
internal class ConnectionManager(
    private val configGenerator: ConfigGenerator,
    private val stateCoordinator: ConnectionStateCoordinator,
    private val log: LogBuffer,
    dependencies: ConnectionManagerDependencies,
) : XrayHealthProbe {
    private val environment = dependencies.environment
    private val rootRuntime = dependencies.rootRuntime
    private val xrayBinary = dependencies.xrayBinary
    private val routingData = dependencies.routingData
    private val serverResolver = dependencies.serverResolver
    private val tunGateway = dependencies.tunGateway
    private val tproxyGateway = dependencies.tproxyGateway
    private val cleanup = dependencies.cleanup
    private val stateStore = dependencies.stateStore
    private val processSupervisor = dependencies.rootProcess
    private val userProcessSupervisor = dependencies.userProcess
    private val diagnostics = dependencies.diagnostics
    private val appRoutingPlanner = dependencies.routingPlanBuilder
    private val activeRouting = dependencies.activeRouting
    private val apiClientFactory = dependencies.apiClientFactory
    private val stepExecutor = ConnectionStepExecutor(
        elapsedRealtime = environment::elapsedRealtime,
        log = { message -> log.append(LogSource.APP, message) },
        onProgressStarted = stateCoordinator::beginConnectionProgress,
        onProgressFinished = stateCoordinator::endConnectionProgress,
    )

    private val rootStrategy: XrayRuntimeStrategy = RootXrayRuntimeStrategy(
        processSupervisor = processSupervisor,
        rootRuntime = rootRuntime,
        cleanup = cleanup,
        xrayBinary = xrayBinary,
    )
    private val vpnServiceStrategy: XrayRuntimeStrategy = VpnServiceXrayRuntimeStrategy(
        processSupervisor = userProcessSupervisor,
        stateStore = stateStore,
        xrayBinary = xrayBinary,
    )

    @Volatile private var xrayStatsClient: ConnectionStatsClient? = null

    @Volatile private var xrayRoutingClient: ConnectionRoutingClient? = null

    private val xrayApiMutex = Mutex()

    private val xrayApiCloseGate = Any()

    private var xrayApiShutdownRequested = false

    @Volatile private var runtimeState: XrayRuntimeState = XrayRuntimeState.Inactive

    @Volatile private var transitionGuardInstalled = false

    @Volatile private var preserveGuardOnFailure = false

    @Volatile private var rootRuntimeKnownClean = false

    // Root is the only runtime that installs routing outside the process; the rootless runtime
    // gets it from Android's VpnService.
    val isUsingRootRuntime: Boolean
        get() = runtimeState.strategy?.managesSystemRouting == true

    @Suppress("CyclomaticComplexMethod")
    suspend fun connect(
        server: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        vpnInterface: ParcelFileDescriptor? = null,
        syntheticDnsAddress: String? = null,
        transitionState: ConnectionState = ConnectionState.Connecting,
        preparation: ConnectionPreparation = ConnectionPreparation.Full,
    ) {
        stateCoordinator.startConnection(transitionState)
        val connectStartedAt = environment.elapsedRealtime()
        val fwmark = runtimeSettings.fwmark
        val routeTable = runtimeSettings.routeTable
        val routeMark = routeTable
        val bypassTable = routeTable + 1
        log.clear(LogSource.XRAY)
        log.append(LogSource.APP, "Connecting to ${server.name} (${server.address}:${server.port})")
        val strategy = strategyFor(useRootService = runtimeSettings.useRootService)
        val managesSystemRouting = strategy.managesSystemRouting
        val rootBackend = effectiveRootBackend(managesSystemRouting, runtimeSettings.rootConnectionBackend)
        runtimeState = XrayRuntimeState.Starting(strategy)

        try {
            val tunName = prepareRuntime(
                strategy = strategy,
                vpnInterface = vpnInterface,
                preparation = preparation,
                configuredTunName = runtimeSettings.tunName,
                rootBackend = rootBackend,
            ) ?: return
            val effectiveRuntimeSettings = runtimeSettings.copy(tunName = tunName)
            if (prepareXrayBinary(strategy, preparation) == null) return
            prepareRoutingData(preparation, transitionState)

            val physicalRouteResult = detectPhysicalRoute(managesSystemRouting, tunName)
            if (!physicalRouteResult.success) return

            val xrayServer = resolveServer(server, runtimeSettings.allowIpv6) ?: return

            val appRoutingPlan = executeStep(
                ConnectionStep("Build app routing plan", ConnectionProgress.ConfiguringRouting) {
                    appRoutingPlanner.build(
                        baseTunName = tunName,
                        baseRouteTable = routeTable,
                        includeProxyRoutes = managesSystemRouting,
                        includeTunRoutes = managesSystemRouting,
                        defaultProxyServer = xrayServer,
                        allowIpv6 = runtimeSettings.allowIpv6,
                    )
                },
            )
            val tproxyPreparation = prepareTproxyPlan(rootBackend, appRoutingPlan, runtimeSettings) ?: return
            val tproxyPlan = tproxyPreparation.plan
            if (hasConfiguredAppRouting(appRoutingPlan)) {
                logAppRoutingPlan(appRoutingPlan)
            }

            val xrayApiEndpoint = strategy.nextApiEndpoint(environment)
            if (!prepareXrayApiAccess(xrayApiEndpoint)) return
            executeStep(
                ConnectionStep("Create Xray control API clients", ConnectionProgress.PreparingCore) {
                    replaceXrayApiClients(xrayApiEndpoint)
                },
            )
            if (
                !prepareTproxyInterception(
                    tproxyPlan = tproxyPlan,
                    tunName = tunName,
                    serverName = server.name,
                    runtimeSettings = runtimeSettings,
                    appRoutingPlan = appRoutingPlan,
                    physicalRoute = physicalRouteResult.route,
                    xrayApiEndpoint = xrayApiEndpoint,
                )
            ) {
                return
            }

            writeXrayConfig(
                xrayServer,
                effectiveRuntimeSettings,
                managesSystemRouting,
                appRoutingPlan,
                physicalRouteResult.route,
                xrayApiEndpoint,
                tproxyPlan,
                syntheticDnsAddress,
            )
            val pid = startXrayProcess(strategy, vpnInterface)

            if (pid <= 0) {
                fail(environment.localizedString(R.string.connection_error_missing_process_id))
                return
            }
            runtimeState = XrayRuntimeState.Active(
                strategy = strategy,
                pid = pid,
                tunName = tunName,
                apiEndpoint = xrayApiEndpoint,
                physicalRoute = physicalRouteResult.route,
            )
            log.append(LogSource.APP, "xray running with PID $pid")
            writeConnectionStateFile(
                pid = pid,
                tunName = tunName,
                serverName = server.name,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appRoutingPlan = appRoutingPlan,
                physicalRoute = physicalRouteResult.route,
                ipRulesApplied = false,
                xrayApiEndpoint = xrayApiEndpoint,
                rootBackend = rootBackend,
                tproxyPlan = tproxyPlan,
            )

            if (
                !finishRuntimeSetup(
                    managesSystemRouting,
                    tunName,
                    fwmark,
                    routeTable,
                    bypassTable,
                    physicalRouteResult.route,
                    runtimeSettings.allowIpv6,
                    appRoutingPlan,
                    pid,
                    rootBackend,
                    tproxyPlan,
                )
            ) {
                return
            }

            finishSuccessfulConnection(
                server = server,
                pid = pid,
                tunName = tunName,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appRoutingPlan = appRoutingPlan,
                physicalRoute = physicalRouteResult.route,
                ipRulesApplied = managesSystemRouting,
                connectStartedAt = connectStartedAt,
                xrayApiEndpoint = xrayApiEndpoint,
                rootBackend = rootBackend,
                tproxyPlan = tproxyPlan,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { cleanCancelledConnectionAttempt() }
            throw error
        } catch (error: IOException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: SecurityException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: IllegalStateException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        } catch (error: SerializationException) {
            fail(error.message ?: environment.localizedString(R.string.error_unknown))
        }
    }

    private fun hasConfiguredAppRouting(plan: AppRoutingPlan): Boolean = plan.proxyRoutes.isNotEmpty() ||
        plan.directUids.isNotEmpty()

    private suspend fun prepareRuntime(
        strategy: XrayRuntimeStrategy,
        vpnInterface: ParcelFileDescriptor?,
        preparation: ConnectionPreparation,
        configuredTunName: String,
        rootBackend: RootConnectionBackend,
    ): String? {
        val customTunName = configuredTunName.trim()
        val persistedKnownCleanState = strategy.managesSystemRouting && cleanup.consumeKnownCleanState()
        val canReuseKnownCleanState = rootRuntimeKnownClean || persistedKnownCleanState
        if (strategy.managesSystemRouting) rootRuntimeKnownClean = false
        if (preparation.cleansPreviousState && strategy.managesSystemRouting) {
            if (canReuseKnownCleanState && !transitionGuardInstalled) {
                log.append(LogSource.APP, "Previous root runtime is already clean")
            } else {
                log.append(LogSource.APP, "Cleaning up previous state...")
                val cleaned = executeStep(
                    ConnectionStep(
                        "Cleanup",
                        ConnectionProgress.PreparingRuntime,
                        isSuccessful = { it },
                        action = {
                            cleanup.ensureCleanState(
                                fallbackTunName = customTunName.ifEmpty { LEGACY_DEFAULT_TUN_NAME },
                                preserveTproxyGuard = transitionGuardInstalled && preserveGuardOnFailure,
                            )
                        },
                    ),
                )
                if (!cleaned) {
                    fail(environment.localizedString(R.string.connection_error_cleanup_failed), cleanState = false)
                    return null
                }
            }
        }

        val ready = if (strategy.managesSystemRouting) {
            prepareRootRuntime(preparation)
        } else {
            prepareVpnServiceRuntime(vpnInterface)
        }
        if (!ready) return null

        val tunName = if (
            strategy.managesSystemRouting &&
            rootBackend == RootConnectionBackend.Tun &&
            customTunName.isEmpty()
        ) {
            executeStep(
                ConnectionStep(
                    "TUN interface name detection",
                    ConnectionProgress.PreparingRuntime,
                    isSuccessful = { it != null },
                    action = tunGateway::findAvailableWlanName,
                ),
            )?.also { selectedName ->
                log.append(LogSource.APP, "Selected available TUN interface name $selectedName")
            } ?: run {
                fail(
                    environment.localizedString(R.string.connection_error_tun_name_detection),
                    cleanState = false,
                )
                return null
            }
        } else if (strategy.managesSystemRouting && rootBackend == RootConnectionBackend.Tproxy) {
            TPROXY_INTERFACE_LABEL
        } else {
            customTunName
        }

        executeStep(
            ConnectionStep("Prepare Xray log file", ConnectionProgress.PreparingRuntime) {
                strategy.prepareLogFile()
            },
        )
        return tunName
    }

    private suspend fun prepareTproxyPlan(
        rootBackend: RootConnectionBackend,
        appRoutingPlan: AppRoutingPlan,
        runtimeSettings: XrayRuntimeSettings,
    ): TproxyPlanPreparation? {
        if (rootBackend != RootConnectionBackend.Tproxy) return TproxyPlanPreparation(null)
        return TproxyPlanPreparation(
            tproxyGateway.createPlan(
                appRoutingPlan = appRoutingPlan,
                routeTable = runtimeSettings.routeTable,
                outboundMark = runtimeSettings.fwmark,
                allowIpv6 = runtimeSettings.allowIpv6,
            ),
        )
    }

    private suspend fun prepareTproxyInterception(
        tproxyPlan: TproxyTrafficPlan?,
        tunName: String,
        serverName: String,
        runtimeSettings: XrayRuntimeSettings,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        xrayApiEndpoint: XrayApiEndpoint,
    ): Boolean {
        if (tproxyPlan == null) return true
        writeConnectionStateFile(
            pid = -1,
            tunName = tunName,
            serverName = serverName,
            fwmark = runtimeSettings.fwmark,
            routeMark = runtimeSettings.routeTable,
            routeTable = runtimeSettings.routeTable,
            bypassTable = runtimeSettings.routeTable + 1,
            appRoutingPlan = appRoutingPlan,
            physicalRoute = physicalRoute,
            ipRulesApplied = false,
            xrayApiEndpoint = xrayApiEndpoint,
            rootBackend = RootConnectionBackend.Tproxy,
            tproxyPlan = tproxyPlan,
        )
        val guardResult = executeStep(
            ConnectionStep(
                "TPROXY startup guard",
                ConnectionProgress.ConfiguringRouting,
                isSuccessful = { it.success },
                action = { tproxyGateway.installGuard(tproxyPlan) },
            ),
        )
        if (!guardResult.success) {
            failRouting(guardResult)
            return false
        }
        transitionGuardInstalled = true
        return true
    }

    private suspend fun prepareRootRuntime(preparation: ConnectionPreparation): Boolean {
        log.append(LogSource.APP, "Requesting root access...")
        val rootGranted = executeStep(
            ConnectionStep(
                "Root shell setup",
                ConnectionProgress.PreparingRuntime,
                isSuccessful = { it },
                action = rootRuntime::open,
            ),
        )
        if (!rootGranted) {
            fail(environment.localizedString(R.string.connection_error_root_access_denied))
            return false
        }
        log.append(
            LogSource.APP,
            "Root access granted (namespace=${rootRuntime.networkNamespaceName()})",
        )
        if (preparation.reusesStaticRuntime) {
            log.append(LogSource.APP, "Runtime exemption check skipped for fast reconnect")
        } else {
            processSupervisor.ensureNativeRuntimeExemptions()
        }
        return true
    }

    private suspend fun prepareVpnServiceRuntime(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (vpnInterface == null) {
            fail(
                environment.localizedString(R.string.connection_error_vpn_permission_required),
                retryable = false,
            )
            return false
        }
        log.append(LogSource.APP, "Using Android VpnService")
        cleanOrphanedVpnServiceRuntime()
        userProcessSupervisor.stop()
        return true
    }

    private suspend fun prepareRootApiAccess(endpoint: XrayApiEndpoint): Boolean {
        if (endpoint !is XrayApiEndpoint.LoopbackTcp) return true
        if (rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return true
        fail(environment.localizedString(R.string.connection_error_secure_xray_api))
        return false
    }

    private suspend fun prepareXrayApiAccess(endpoint: XrayApiEndpoint): Boolean = if (endpoint is XrayApiEndpoint.LoopbackTcp) {
        executeStep(
            ConnectionStep(
                "Xray API firewall setup",
                ConnectionProgress.PreparingCore,
                isSuccessful = { it },
                action = { prepareRootApiAccess(endpoint) },
            ),
        )
    } else {
        prepareRootApiAccess(endpoint)
    }

    private suspend fun cleanOrphanedVpnServiceRuntime() {
        val staleState = stateStore.read()
            ?.takeIf { it.physicalInterface == VPN_SERVICE_INTERFACE_LABEL }
            ?: return
        userProcessSupervisor.stopOrphan(staleState.xrayPid)
        stateStore.delete()
    }

    private suspend fun prepareXrayBinary(strategy: XrayRuntimeStrategy, preparation: ConnectionPreparation): String? {
        val verifyAvailable = !preparation.reusesStaticRuntime
        if (verifyAvailable) {
            log.append(LogSource.APP, "Extracting xray binary...")
        } else {
            log.append(LogSource.APP, "xray binary extraction skipped for fast reconnect")
        }
        val activeBinaryPath = if (verifyAvailable) {
            executeStep(
                ConnectionStep(
                    "xray binary setup",
                    ConnectionProgress.PreparingCore,
                    isSuccessful = { it != null },
                    action = { strategy.prepareBinary(verifyAvailable = true) },
                ),
            )
        } else {
            strategy.prepareBinary(verifyAvailable = false)
        }
        if (activeBinaryPath == null) {
            fail(environment.localizedString(R.string.connection_error_xray_binary_not_found))
            return null
        }
        log.append(LogSource.APP, "xray binary ready at $activeBinaryPath")
        return activeBinaryPath
    }

    private suspend fun prepareRoutingData(preparation: ConnectionPreparation, transitionState: ConnectionState) {
        if (preparation.reusesStaticRuntime) {
            log.append(LogSource.APP, "Routing data check skipped for fast reconnect")
            return
        }

        log.append(LogSource.APP, "Checking routing data...")
        if (routingData.needsRefresh()) {
            stateCoordinator.markUpdatingRoutingData()
            log.append(LogSource.APP, "Updating routing data...")
        }
        val geoDataStatus = executeStep(
            ConnectionStep(
                "Routing data setup",
                ConnectionProgress.UpdatingRoutingData,
                action = routingData::ensureReady,
            ),
        )
        stateCoordinator.startConnection(transitionState)
        if (geoDataStatus.downloaded) {
            log.append(
                LogSource.APP,
                "Routing data updated (geoip=${geoDataStatus.geoipUrl}, geosite=${geoDataStatus.geositeUrl})",
            )
        } else {
            log.append(LogSource.APP, "Routing data already up to date")
        }
    }

    private suspend fun detectPhysicalRoute(managesSystemRouting: Boolean, tunName: String): PhysicalRouteResult {
        if (!managesSystemRouting) return PhysicalRouteResult(success = true, route = null)

        val route = executeStep(
            ConnectionStep(
                "Physical route detection",
                progress = null,
                retryable = true,
                maxRetries = CONNECTION_STEP_MAX_RETRIES,
                retryDelayMs = CONNECTION_STEP_RETRY_DELAY_MS,
                isSuccessful = { it != null },
                reported = false,
                action = { tunGateway.detectPhysicalRoute(tunName) },
            ),
        )
        if (route == null) {
            fail(environment.localizedString(R.string.connection_error_physical_route_not_found))
            return PhysicalRouteResult(success = false, route = null)
        }
        log.append(
            LogSource.APP,
            "Physical bypass route: dev=${route.dev}" +
                (route.gateway?.let { " via=$it" } ?: "") +
                (route.table?.let { " table=$it" } ?: ""),
        )
        return PhysicalRouteResult(success = true, route = route)
    }

    private suspend fun resolveServer(server: ServerConfig, allowIpv6: Boolean): ServerConfig? {
        val resolvedServer = executeStep(
            ConnectionStep(
                "Server address resolution",
                ConnectionProgress.ResolvingEntryServer,
                retryable = true,
                maxRetries = CONNECTION_STEP_MAX_RETRIES,
                retryDelayMs = CONNECTION_STEP_RETRY_DELAY_MS,
                isSuccessful = { !it.attempted || it.selectedAddress != null },
                action = { serverResolver.resolve(server, allowIpv6) },
            ),
        )
        if (resolvedServer.attempted && resolvedServer.selectedAddress == null) {
            val unresolvedHost = resolvedServer.unresolvedHosts.firstOrNull() ?: server.address
            fail(environment.localizedString(R.string.connection_error_server_address_unresolved, unresolvedHost))
            return null
        }
        if (resolvedServer.server.bootstrapDnsHosts.isNotEmpty()) {
            log.append(
                LogSource.APP,
                "Resolved ${resolvedServer.server.bootstrapDnsHosts.size} raw config endpoint hostname(s) " +
                    "(${resolvedServer.candidates.size} candidates)",
            )
        } else if (resolvedServer.selectedAddress != null) {
            log.append(
                LogSource.APP,
                "Resolved ${server.address} to ${resolvedServer.selectedAddress} (${resolvedServer.candidates.size} candidates)",
            )
        }
        return resolvedServer.server
    }

    private suspend fun writeXrayConfig(
        xrayServer: ServerConfig,
        runtimeSettings: XrayRuntimeSettings,
        managesSystemRouting: Boolean,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        xrayApiEndpoint: XrayApiEndpoint,
        tproxyPlan: TproxyTrafficPlan?,
        syntheticDnsAddress: String?,
    ) {
        val configJson = executeStep(
            ConnectionStep(
                "Config generation",
                ConnectionProgress.GeneratingConfiguration,
                action = {
                    withContext(Dispatchers.Default) {
                        configGenerator.generate(
                            server = xrayServer,
                            tunName = runtimeSettings.tunName,
                            fwmark = runtimeSettings.fwmark.takeIf { managesSystemRouting } ?: 0,
                            dnsServers = runtimeSettings.dnsServers,
                            domesticDnsServers = runtimeSettings.domesticDnsServers,
                            syntheticDnsAddress = syntheticDnsAddress,
                            logLevel = runtimeSettings.logLevel,
                            defaultOutbound = runtimeSettings.defaultOutbound,
                            bypassLan = runtimeSettings.bypassLan,
                            allowIpv6 = runtimeSettings.allowIpv6,
                            routingRules = runtimeSettings.routingRules,
                            routingDomainStrategy = runtimeSettings.routingDomainStrategy,
                            routingDomainMatcher = runtimeSettings.routingDomainMatcher,
                            routingFallbackOutbound = runtimeSettings.routingFallbackOutbound,
                            appProxyRoutes = appRoutingPlan.proxyRoutes,
                            physicalInterface = physicalRoute?.dev,
                            xrayApiEndpoint = xrayApiEndpoint,
                            xrayBufferSizeKiB = runtimeSettings.xrayBufferSizeKiB,
                            tunMtu = runtimeSettings.tunMtu,
                            inbounds = tproxyPlan?.runtimeState?.groups?.map { group ->
                                XrayInbound.Tproxy(
                                    port = group.port,
                                    tag = group.inboundTag,
                                    outboundMark = runtimeSettings.fwmark,
                                    allowIpv6 = runtimeSettings.allowIpv6,
                                )
                            },
                        )
                    }
                },
            ),
        )
        executeStep(
            ConnectionStep(
                "Config write",
                ConnectionProgress.GeneratingConfiguration,
                action = { xrayBinary.writeConfig(configJson) },
            ),
        )
        log.append(LogSource.APP, "Config written to ${xrayBinary.configPath()} (${configJson.length} chars)")
    }

    private fun logAppRoutingPlan(appRoutingPlan: AppRoutingPlan) {
        log.append(
            LogSource.APP,
            "App routing: ${appRoutingPlan.proxyRoutes.sumOf { route ->
                appRoutingPlan.tunRoutes.firstOrNull { it.tunName == route.tunName }?.uids?.size ?: 0
            }} apps assigned to ${appRoutingPlan.proxyRoutes.size} proxy route(s), ${appRoutingPlan.directUids.size} apps direct",
        )
    }

    private suspend fun startXrayProcess(strategy: XrayRuntimeStrategy, vpnInterface: ParcelFileDescriptor?): Int {
        log.append(LogSource.APP, "Starting xray process...")
        return executeStep(
            ConnectionStep(
                "xray process launch",
                ConnectionProgress.StartingCore,
                isSuccessful = { it > 0 },
                action = { strategy.startProcess(binDir = environment.binDir, vpnInterface = vpnInterface) },
            ),
        )
    }

    private suspend fun writeConnectionStateFile(
        pid: Int,
        tunName: String,
        serverName: String,
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        bypassTable: Int,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        ipRulesApplied: Boolean,
        xrayApiEndpoint: XrayApiEndpoint,
        rootBackend: RootConnectionBackend,
        tproxyPlan: TproxyTrafficPlan?,
    ) {
        val transitionGuard = if (transitionGuardInstalled && preserveGuardOnFailure) {
            stateStore.read()?.let { it.tproxy ?: it.transitionGuard }
        } else {
            null
        }
        stateStore.write(
            XrayState(
                appVersionCode = environment.appVersionCode,
                xrayPid = pid,
                xrayApiPort = (xrayApiEndpoint as? XrayApiEndpoint.LoopbackTcp)?.port,
                tunName = tunName,
                serverName = serverName,
                ipRulesApplied = ipRulesApplied,
                fwmark = fwmark,
                routeMark = routeMark,
                routeTable = routeTable,
                bypassTable = bypassTable,
                appProxyServerIds = appRoutingPlan.proxyServerIds,
                physicalInterface = physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
                rootConnectionBackend = rootBackend,
                tproxy = tproxyPlan?.runtimeState,
                transitionGuard = transitionGuard,
            ),
        )
    }

    private suspend fun waitForRootTun(managesSystemRouting: Boolean, tunName: String, allowIpv6: Boolean, pid: Int): Boolean {
        if (!managesSystemRouting) return true

        log.append(LogSource.APP, "Waiting for TUN interface '$tunName'...")
        val tunSetup = executeStep(
            ConnectionStep(
                "TUN setup",
                ConnectionProgress.ConfiguringTunnel,
                isSuccessful = { it.success },
                action = {
                    tunGateway.configureTun(
                        tunName = tunName,
                        addressCidr = TunManager.DEFAULT_TUN_ADDRESS_CIDR,
                        ipv6AddressCidr = TunManager.DEFAULT_TUN_IPV6_ADDRESS_CIDR.takeIf { allowIpv6 },
                    ) { isProcessAlive(pid) }
                },
            ),
        )
        if (!tunSetup.success) {
            handleTunSetupFailure(tunSetup, tunName, pid, diagnosticsStage = "tun")
            return false
        }
        log.append(LogSource.APP, "TUN interface $tunName is up")
        return true
    }

    private suspend fun finishRuntimeSetup(
        managesSystemRouting: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        allowIpv6: Boolean,
        appRoutingPlan: AppRoutingPlan,
        pid: Int,
        rootBackend: RootConnectionBackend,
        tproxyPlan: TproxyTrafficPlan?,
    ): Boolean {
        if (rootBackend == RootConnectionBackend.Tproxy && tproxyPlan != null) {
            if (!waitForXrayApiReady(pid)) return false
            val routingResult = executeStep(
                ConnectionStep(
                    "TPROXY routing setup",
                    ConnectionProgress.ConfiguringRouting,
                    isSuccessful = { it.success },
                    action = { tproxyGateway.activate(tproxyPlan) },
                ),
            )
            if (!routingResult.success) {
                diagnostics.logTproxyDiagnostics("tproxy-activation-failure", tproxyPlan.runtimeState, pid)
                failRouting(routingResult)
                return false
            }
            val healthy = executeStep(
                ConnectionStep(
                    "TPROXY routing verification",
                    ConnectionProgress.ConfiguringRouting,
                    isSuccessful = { it },
                    action = { tproxyGateway.verify(tproxyPlan.runtimeState) },
                ),
            )
            if (!healthy) {
                diagnostics.logTproxyDiagnostics("tproxy-health-failure", tproxyPlan.runtimeState, pid)
                fail(environment.localizedString(R.string.connection_error_tproxy_health_check))
                return false
            }
            log.append(LogSource.APP, "TPROXY routing applied")
            return finishTransitionGuard()
        }
        if (
            !waitForRootTun(
                managesSystemRouting = managesSystemRouting,
                tunName = tunName,
                allowIpv6 = allowIpv6,
                pid = pid,
            )
        ) {
            return false
        }
        if (!waitForAppTuns(appRoutingPlan = appRoutingPlan, allowIpv6 = allowIpv6, pid = pid)) return false
        if (
            !applyRootRouting(
                managesSystemRouting,
                tunName,
                fwmark,
                routeTable,
                bypassTable,
                physicalRoute,
                allowIpv6,
                appRoutingPlan,
            )
        ) {
            return false
        }
        if (!waitForXrayApiReady(pid)) return false
        return finishTransitionGuard()
    }

    private suspend fun finishTransitionGuard(): Boolean {
        if (!transitionGuardInstalled) return true
        val removed = executeStep(
            ConnectionStep(
                "TPROXY transition guard removal",
                ConnectionProgress.ConfiguringRouting,
                isSuccessful = { it },
                action = tproxyGateway::removeGuard,
            ),
        )
        if (!removed) {
            fail(environment.localizedString(R.string.connection_error_cleanup_failed))
            return false
        }
        transitionGuardInstalled = false
        preserveGuardOnFailure = false
        stateStore.read()?.let { state ->
            if (state.transitionGuard != null) stateStore.write(state.copy(transitionGuard = null))
        }
        return true
    }

    private suspend fun failRouting(result: TunManager.RoutingResult) {
        fail(
            environment.localizedString(
                R.string.connection_error_apply_ip_routing,
                result.error ?: environment.localizedString(R.string.error_unknown),
            ),
        )
    }

    private suspend fun waitForAppTuns(appRoutingPlan: AppRoutingPlan, allowIpv6: Boolean, pid: Int): Boolean {
        appRoutingPlan.tunRoutes.forEachIndexed { index, route ->
            log.append(LogSource.APP, "Waiting for app TUN interface '${route.tunName}'...")
            val appTunSetup = executeStep(
                ConnectionStep(
                    "App TUN setup ${index + 1}",
                    ConnectionProgress.ConfiguringTunnel,
                    isSuccessful = { it.success },
                    action = {
                        tunGateway.configureTun(
                            tunName = route.tunName,
                            addressCidr = TunManager.appTunAddressCidr(index + 1),
                            ipv6AddressCidr = TunManager.appTunIpv6AddressCidr(index + 1).takeIf { allowIpv6 },
                        ) { isProcessAlive(pid) }
                    },
                ),
            )
            if (!appTunSetup.success) {
                handleTunSetupFailure(appTunSetup, route.tunName, pid, diagnosticsStage = "app-tun")
                return false
            }
            log.append(LogSource.APP, "App TUN interface ${route.tunName} is up")
        }
        return true
    }

    private suspend fun handleTunSetupFailure(
        tunSetup: TunManager.TunSetupResult,
        tunName: String,
        pid: Int,
        diagnosticsStage: String,
    ) {
        val stage = if (tunSetup.processExited) "$diagnosticsStage-exit" else "$diagnosticsStage-failure"
        diagnostics.logNamespaceDiagnostics(stage = stage, tunName = tunName, xrayPid = pid)
        if (tunSetup.processExited) {
            fail(environment.localizedString(R.string.connection_error_xray_crashed, readCrashReason()))
        } else {
            fail(
                tunSetup.error
                    ?: environment.localizedString(R.string.connection_error_tun_timeout, tunName),
            )
        }
    }

    private suspend fun applyRootRouting(
        managesSystemRouting: Boolean,
        tunName: String,
        fwmark: Int,
        routeTable: Int,
        bypassTable: Int,
        physicalRoute: TunManager.PhysicalRoute?,
        allowIpv6: Boolean,
        appRoutingPlan: AppRoutingPlan,
    ): Boolean {
        if (!managesSystemRouting) return true

        val bypassUids = runtimeBypassUids(appRoutingPlan.directUids)
        log.append(
            LogSource.APP,
            "Applying IP routing (tunTable=$routeTable, bypassTable=$bypassTable, fwmark=$fwmark, ${bypassUids.size} apps direct, ${appRoutingPlan.tunRoutes.size} app proxy route(s))...",
        )
        val routingResult = executeStep(
            ConnectionStep(
                "IP routing setup",
                ConnectionProgress.ConfiguringRouting,
                isSuccessful = { it.success },
                action = {
                    tunGateway.applyRouting(
                        tunName = tunName,
                        fwmark = fwmark,
                        routeTable = routeTable,
                        bypassTable = bypassTable,
                        physicalRoute = requireNotNull(physicalRoute),
                        allowIpv6 = allowIpv6,
                        bypassUids = bypassUids,
                        appTunRoutes = appRoutingPlan.tunRoutes,
                        managedAppRouteCount = appRoutingPlan.tunRoutes.size,
                        routeProfileIds = appRoutingPlan.routeProfileIds,
                    )
                },
            ),
        )
        if (!routingResult.success) {
            fail(
                environment.localizedString(
                    R.string.connection_error_apply_ip_routing,
                    routingResult.error ?: environment.localizedString(R.string.error_unknown),
                ),
            )
            return false
        }
        log.append(LogSource.APP, "IP routing applied")
        return true
    }

    private suspend fun waitForXrayApiReady(pid: Int): Boolean = executeStep(
        ConnectionStep(
            "Xray API readiness",
            ConnectionProgress.WaitingForCore,
            isSuccessful = { it },
            action = { awaitXrayApiReady(pid) },
        ),
    )

    private suspend fun awaitXrayApiReady(pid: Int): Boolean {
        val deadline = environment.elapsedRealtime() + XRAY_API_READY_TIMEOUT_MS
        do {
            if (!isProcessAlive(pid)) {
                fail(environment.localizedString(R.string.connection_error_xray_crashed, readCrashReason()))
                return false
            }
            if (readXraySysStats() != null) return true
            val remainingMs = deadline - environment.elapsedRealtime()
            if (remainingMs <= 0) break
            delay(minOf(XRAY_API_READY_RETRY_DELAY_MS, remainingMs))
        } while (true)

        fail(environment.localizedString(R.string.connection_error_xray_api_not_ready))
        return false
    }

    private suspend fun finishSuccessfulConnection(
        server: ServerConfig,
        pid: Int,
        tunName: String,
        fwmark: Int,
        routeMark: Int,
        routeTable: Int,
        bypassTable: Int,
        appRoutingPlan: AppRoutingPlan,
        physicalRoute: TunManager.PhysicalRoute?,
        ipRulesApplied: Boolean,
        connectStartedAt: Long,
        xrayApiEndpoint: XrayApiEndpoint,
        rootBackend: RootConnectionBackend,
        tproxyPlan: TproxyTrafficPlan?,
    ) {
        writeConnectionStateFile(
            pid = pid,
            tunName = tunName,
            serverName = server.name,
            fwmark = fwmark,
            routeMark = routeMark,
            routeTable = routeTable,
            bypassTable = bypassTable,
            appRoutingPlan = appRoutingPlan,
            physicalRoute = physicalRoute,
            ipRulesApplied = ipRulesApplied,
            xrayApiEndpoint = xrayApiEndpoint,
            rootBackend = rootBackend,
            tproxyPlan = tproxyPlan,
        )

        log.append(LogSource.APP, "Connected to ${server.name}")
        log.append(
            LogSource.APP,
            "Connection setup finished in ${environment.elapsedRealtime() - connectStartedAt} ms",
        )
        stateCoordinator.markConnected(
            ConnectionState.Connected(
                serverName = server.name,
                corePid = pid,
                tunName = tunName,
                physicalInterface = physicalRoute?.dev ?: VPN_SERVICE_INTERFACE_LABEL,
                physicalGateway = physicalRoute?.gateway,
                physicalTable = physicalRoute?.table,
            ),
        )
    }

    private data class PhysicalRouteResult(
        val success: Boolean,
        val route: TunManager.PhysicalRoute?,
    )

    suspend fun applyAppRoutingChanges(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean = executeStep(
        ConnectionStep(
            label = "Fast app routing update",
            progress = ConnectionProgress.UpdatingAppRouting,
            isSuccessful = { it },
            action = { applyAppRoutingChangesOnce(connectedState, runtimeSettings) },
        ),
    )

    private suspend fun applyAppRoutingChangesOnce(
        connectedState: ConnectionState.Connected,
        runtimeSettings: XrayRuntimeSettings,
    ): Boolean {
        if (runtimeSettings.rootConnectionBackend != RootConnectionBackend.Tproxy) {
            return activeRouting.applyAppRoutingChanges(
                connectedState = connectedState,
                tunName = connectedState.tunName,
                fwmark = runtimeSettings.fwmark,
                routeTable = runtimeSettings.routeTable,
                allowIpv6 = runtimeSettings.allowIpv6,
            )
        }

        val persistedState = stateStore.read() ?: return false
        val tproxyState = persistedState.tproxy ?: return false
        if (persistedState.appProxyServerIds.isEmpty() && tproxyState.groups.size > 1) return false
        if (!isProcessAlive(connectedState.corePid)) return false
        val appRoutingPlan = appRoutingPlanner.build(
            baseTunName = TPROXY_INTERFACE_LABEL,
            baseRouteTable = runtimeSettings.routeTable,
            includeProxyRoutes = false,
            includeTunRoutes = true,
            allowIpv6 = runtimeSettings.allowIpv6,
        )
        if (appRoutingPlan.proxyServerIds != persistedState.appProxyServerIds) return false
        val plan = tproxyGateway.createPlan(
            appRoutingPlan = appRoutingPlan,
            routeTable = runtimeSettings.routeTable,
            outboundMark = runtimeSettings.fwmark,
            allowIpv6 = runtimeSettings.allowIpv6,
            existingState = tproxyState,
        )
        val result = executeStep(
            ConnectionStep(
                "TPROXY app routing update",
                ConnectionProgress.UpdatingAppRouting,
                isSuccessful = { it.success },
                action = { tproxyGateway.update(plan, tproxyState.outputChainSlot) },
            ),
        )
        if (!result.success) {
            log.append(LogSource.APP, "Fast TPROXY app routing update skipped: ${result.error ?: "unknown error"}")
            return false
        }
        val nextSlot = if (tproxyState.outputChainSlot == "a") "b" else "a"
        stateStore.write(
            persistedState.copy(
                tproxy = tproxyState.copy(outputChainSlot = nextSlot),
                ipRulesApplied = true,
            ),
        )
        return true
    }

    suspend fun updatePhysicalBypassRoute(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult = executeStep(
        ConnectionStep(
            label = "Update active physical route",
            progress = ConnectionProgress.UpdatingNetworkRoute,
            isSuccessful = { it is PhysicalRouteUpdateResult.Applied },
            action = { updatePhysicalBypassRouteOnce(connectedState, physicalRoute, runtimeSettings) },
        ),
    )

    private suspend fun updatePhysicalBypassRouteOnce(
        connectedState: ConnectionState.Connected,
        physicalRoute: TunManager.PhysicalRoute,
        runtimeSettings: XrayRuntimeSettings,
    ): PhysicalRouteUpdateResult {
        val persistedState = stateStore.read()
        if (persistedState?.rootConnectionBackend == RootConnectionBackend.Tproxy) {
            if (connectedState.physicalInterface != physicalRoute.dev) return PhysicalRouteUpdateResult.RequiresReconnect
            stateStore.write(
                persistedState.copy(
                    physicalInterface = physicalRoute.dev,
                    physicalGateway = physicalRoute.gateway,
                    physicalTable = physicalRoute.table,
                ),
            )
            return PhysicalRouteUpdateResult.Applied(physicalRoute)
        }
        return activeRouting.updatePhysicalBypassRoute(
            connectedState = connectedState,
            physicalRoute = physicalRoute,
            tunName = connectedState.tunName,
            fwmark = runtimeSettings.fwmark,
            routeTable = runtimeSettings.routeTable,
        )
    }

    suspend fun isRootTrafficAvailable(tunAvailable: Boolean): Boolean {
        val state = stateStore.read() ?: return false
        val tproxyState = state.tproxy
        return if (state.rootConnectionBackend == RootConnectionBackend.Tproxy && tproxyState != null) {
            tproxyGateway.verify(tproxyState)
        } else {
            tunAvailable
        }
    }

    suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? = executeStep(
        ConnectionStep(
            "Physical route probe",
            progress = null,
            isSuccessful = { it != null },
            reported = false,
            action = {
                if (!rootRuntime.open()) return@ConnectionStep null
                tunGateway.detectPhysicalRoute(tunName)
            },
        ),
    )

    suspend fun restoreRootApiClients(): Boolean = executeStep(
        ConnectionStep(
            label = "Restore Xray control API",
            progress = ConnectionProgress.RestoringControlApi,
            isSuccessful = { it },
            action = ::restoreRootApiClientsOnce,
        ),
    )

    private suspend fun restoreRootApiClientsOnce(): Boolean {
        // Validate the persisted state before touching the firewall: installing loopback rules
        // for a runtime that turns out to be rootless or unreadable would leave orphaned iptables
        // state behind with nothing to reattach to.
        val state = stateStore.read() ?: return false
        // A record left by the rootless runtime describes a core that died with its process, so
        // there is nothing here to reattach to.
        if (state.physicalInterface == VPN_SERVICE_INTERFACE_LABEL) return false
        val configuredEndpoint = xrayBinary.readConfig()?.let(::parseXrayApiEndpoint)
        val endpoint = when (configuredEndpoint) {
            is XrayApiEndpoint.LoopbackTcp -> configuredEndpoint
            is XrayApiEndpoint.UnixSocket -> return false
            null ->
                state.xrayApiPort
                    ?.takeIf { it in 1..65_535 }
                    ?.let { XrayApiEndpoint.LoopbackTcp(it) }
                    ?: return false
        }
        if (!rootRuntime.protectLoopbackApi(endpoint.port, environment.appUid)) return false
        runtimeState = XrayRuntimeState.Active(
            strategy = rootStrategy,
            pid = state.xrayPid,
            tunName = state.tunName,
            apiEndpoint = endpoint,
            physicalRoute = selectPersistedPhysicalRoute(state),
        )
        replaceXrayApiClients(endpoint)
        return true
    }

    suspend fun disconnect(): Boolean = disconnect(updateState = true, fastCleanup = true)

    suspend fun disconnect(
        updateState: Boolean,
        fastCleanup: Boolean = false,
        preserveTproxyGuard: Boolean = false,
    ): Boolean {
        if (updateState) {
            stateCoordinator.markDisconnecting()
            log.append(LogSource.APP, "Disconnecting...")
        }
        // With no runtime of our own and nothing recorded by an earlier one, there is nothing
        // installed to take back.
        val strategy = runtimeState.strategy ?: persistedRuntimeStrategy()
        val cleaned = executeStep(
            ConnectionStep(
                label = "Release Xray runtime",
                progress = ConnectionProgress.StoppingCore,
                isSuccessful = { it },
                action = {
                    strategy
                        ?.release(fastCleanup = fastCleanup, preserveTproxyGuard = preserveTproxyGuard)
                        ?: true
                },
            ),
        )
        rootRuntimeKnownClean = cleaned && strategy?.managesSystemRouting == true && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        runtimeState = XrayRuntimeState.Inactive
        executeStep(
            ConnectionStep("Close Xray control API", ConnectionProgress.CleaningRuntime) {
                closeXrayApiClients()
            },
        )
        if (!cleaned) {
            stateCoordinator.markError(environment.localizedString(R.string.connection_error_cleanup_failed))
            return false
        }
        if (!preserveTproxyGuard) {
            // The release above took the guard chain back down. Carrying these flags into an unrelated
            // later connection attempt would make its failure path preserve a guard that DROPs every
            // app UID, taking the whole device offline instead of just reporting a failed connection.
            transitionGuardInstalled = false
            preserveGuardOnFailure = false
        }
        if (updateState) {
            log.append(LogSource.APP, "Disconnected")
            stateCoordinator.markDisconnected()
        }
        return true
    }

    suspend fun prepareSeamlessReconnect(): Boolean {
        val state = stateStore.read() ?: return true
        val tproxyState = state.tproxy ?: state.transitionGuard ?: return true
        val appRoutingPlan = try {
            appRoutingPlanner.build(
                baseTunName = TPROXY_INTERFACE_LABEL,
                baseRouteTable = state.routeTable,
                includeProxyRoutes = false,
                includeTunRoutes = true,
            )
        } catch (error: IllegalArgumentException) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${error.message}")
            return false
        } catch (error: IllegalStateException) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${error.message}")
            return false
        } catch (error: SerializationException) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${error.message}")
            return false
        }
        val plan = TproxyTrafficPlan(
            runtimeState = tproxyState,
            groups = tproxyState.groups.mapIndexed { index, group ->
                com.material.xray.core.xray.TproxyTrafficGroup(group, emptySet(), isBase = index == 0)
            },
            bypassUids = runtimeBypassUids(appRoutingPlan.directUids),
            routeProfileIds = appRoutingPlan.routeProfileIds,
            outboundMark = state.fwmark,
        )
        val result = tproxyGateway.installGuard(plan)
        if (!result.success) {
            log.append(LogSource.APP, "Could not prepare TPROXY reconnect guard: ${result.error ?: "unknown error"}")
            return false
        }
        transitionGuardInstalled = true
        preserveGuardOnFailure = true
        return true
    }

    val hasTransitionGuard: Boolean
        get() = transitionGuardInstalled

    fun prepareForServiceDestruction() {
        runtimeState.strategy?.requestStop()
        requestXrayApiClientClose()
    }

    suspend fun adoptPersistedTransitionGuard(): Boolean {
        val state = stateStore.read() ?: return false
        if (state.transitionGuard == null && state.tproxy == null) return false
        if (!tproxyGateway.hasGuard()) return false
        transitionGuardInstalled = true
        preserveGuardOnFailure = true
        return true
    }

    suspend fun ensureCleanRootRuntime(preserveTproxyGuard: Boolean = false): Boolean {
        val cleaned = executeStep(
            ConnectionStep(
                label = "Clean recorded root runtime",
                progress = ConnectionProgress.CleaningRuntime,
                isSuccessful = { it },
                action = { cleanup.ensureCleanState(preserveTproxyGuard = preserveTproxyGuard) },
            ),
        )
        rootRuntimeKnownClean = cleaned && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        runtimeState = XrayRuntimeState.Inactive
        if (!cleaned) stateCoordinator.markError(environment.localizedString(R.string.connection_error_cleanup_failed))
        return cleaned
    }

    suspend fun clearFailedTransitionGuard() {
        if (!transitionGuardInstalled) return
        cleanup.ensureCleanState(preserveTproxyGuard = false)
        transitionGuardInstalled = false
        preserveGuardOnFailure = false
    }

    private suspend fun fail(
        message: String,
        cleanState: Boolean = true,
        retryable: Boolean = true,
    ) {
        log.append(LogSource.APP, "ERROR: $message")
        var finalMessage = message
        if (cleanState) {
            if (!releaseStartedRuntime(preserveGuardOnFailure)) {
                finalMessage = environment.localizedString(R.string.connection_error_cleanup_failed)
                log.append(LogSource.APP, "ERROR: $finalMessage")
            }
            if (transitionGuardInstalled && !preserveGuardOnFailure) {
                if (!tproxyGateway.removeGuard()) {
                    finalMessage = environment.localizedString(R.string.connection_error_cleanup_failed)
                    log.append(LogSource.APP, "ERROR: $finalMessage")
                } else {
                    transitionGuardInstalled = false
                }
            }
            runtimeState = XrayRuntimeState.Inactive
        }
        closeXrayApiClients()
        stateCoordinator.markError(finalMessage, retryable)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun cleanCancelledConnectionAttempt() {
        val cleanupErrors = mutableListOf<Exception>()
        try {
            if (!releaseStartedRuntime(preserveGuardOnFailure)) {
                log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup")
            }
        } catch (error: Exception) {
            cleanupErrors += error
        } finally {
            runtimeState = XrayRuntimeState.Inactive
            if (transitionGuardInstalled && !preserveGuardOnFailure) {
                try {
                    if (tproxyGateway.removeGuard()) transitionGuardInstalled = false
                } catch (error: Exception) {
                    cleanupErrors += error
                }
            }
            try {
                closeXrayApiClients()
            } catch (error: Exception) {
                cleanupErrors += error
            }
        }
        cleanupErrors.forEach { error ->
            log.append(LogSource.APP, "ERROR: Could not clean up cancelled Xray startup: ${error.message}")
        }
        stateCoordinator.markDisconnected()
    }

    override suspend fun isProcessAlive(pid: Int): Boolean = processFor(pid)?.isAlive(pid) ?: false

    suspend fun isRestorableRootProcessAlive(pid: Int): Boolean = executeStep(
        ConnectionStep(
            "Restored Xray process check",
            ConnectionProgress.VerifyingRuntime,
            isSuccessful = { it },
            action = { processSupervisor.isAlive(pid) },
        ),
    )

    suspend fun isRestorableRootRoutingAvailable(state: XrayState, tunAvailable: Boolean): Boolean = executeStep(
        ConnectionStep(
            "Restored routing check",
            ConnectionProgress.VerifyingRuntime,
            isSuccessful = { it },
            action = {
                val tproxyState = state.tproxy
                if (state.rootConnectionBackend == RootConnectionBackend.Tproxy && tproxyState != null) {
                    tproxyGateway.verify(tproxyState)
                } else {
                    tunAvailable
                }
            },
        ),
    )

    suspend fun killProcess(pid: Int, signal: Int = 15): Boolean = processFor(pid)?.kill(pid, signal) ?: false

    override suspend fun readProcessResidentMemoryMb(pid: Int): Long? = processFor(pid)?.readResidentMemoryMb(pid)

    suspend fun readActiveConnectionCount(pid: Int): Int? = processFor(pid)?.readActiveConnectionCount(pid)

    suspend fun readProcessMetrics(pid: Int): ProcessMetrics? = processFor(pid)?.readProcessMetrics(pid)

    suspend fun readOutboundTrafficStatsBytes(): Map<String, Long> = withXrayApiClients {
        xrayStatsClient?.queryOutboundTrafficStatsBytes().orEmpty()
    }

    override suspend fun readXraySysStats(): XraySysStats? = withXrayApiClients { xrayStatsClient?.getSysStats() }

    override suspend fun readCrashReason(): String = runCatching {
        runtimeState.strategy?.readCrashReason()
    }.getOrNull() ?: "xray process exited"

    internal suspend fun readBalancerSelection(balancerTag: String) = withXrayApiClients {
        xrayRoutingClient?.queryBalancerSelection(balancerTag)
    }

    private suspend fun replaceXrayApiClients(endpoint: XrayApiEndpoint) = withXrayApiClients {
        replaceXrayApiClientsLocked(endpoint)
    }

    private fun replaceXrayApiClientsLocked(endpoint: XrayApiEndpoint) {
        closeXrayApiClientsLocked()
        apiClientFactory.create(endpoint).also { clients ->
            xrayStatsClient = clients.stats
            xrayRoutingClient = clients.routing
        }
    }

    private suspend fun closeXrayApiClients() = withXrayApiClients {
        closeXrayApiClientsLocked()
    }

    private fun closeXrayApiClientsLocked() {
        xrayStatsClient?.close()
        xrayStatsClient = null
        xrayRoutingClient?.close()
        xrayRoutingClient = null
    }

    private suspend fun <T> withXrayApiClients(block: suspend () -> T): T {
        xrayApiMutex.lock()
        try {
            return block()
        } finally {
            synchronized(xrayApiCloseGate) {
                try {
                    if (xrayApiShutdownRequested) {
                        closeXrayApiClientsLocked()
                    }
                } finally {
                    xrayApiMutex.unlock()
                }
            }
        }
    }

    private fun requestXrayApiClientClose() {
        synchronized(xrayApiCloseGate) {
            xrayApiShutdownRequested = true
            if (!xrayApiMutex.tryLock()) return
            try {
                closeXrayApiClientsLocked()
            } finally {
                xrayApiMutex.unlock()
            }
        }
    }

    private fun runtimeBypassUids(directUids: Set<Int>): Set<Int> {
        val appUid = environment.appUid
        return if (appUid > 0) directUids + appUid else directUids
    }

    private fun strategyFor(useRootService: Boolean): XrayRuntimeStrategy = if (useRootService) {
        rootStrategy
    } else {
        vpnServiceStrategy
    }

    /** Resolves the core that [pid] belongs to, or null when this manager did not start it. */
    private fun processFor(pid: Int): XrayRuntimeProcess? = (runtimeState as? XrayRuntimeState.Active)
        ?.takeIf { it.pid == pid }
        ?.strategy

    private suspend fun persistedRuntimeStrategy(): XrayRuntimeStrategy? = stateStore.read()?.let { state ->
        strategyFor(useRootService = state.physicalInterface != VPN_SERVICE_INTERFACE_LABEL)
    }

    /**
     * Releases the runtime a connection attempt had already started.
     *
     * The root fallback covers teardown after the runtime was already reset, which happens when a
     * failure is itself cancelled part-way through; root cleanup is the safe choice there because
     * it is the only runtime that can leave routing behind.
     */
    private suspend fun releaseStartedRuntime(preserveTproxyGuard: Boolean = false): Boolean {
        val strategy = runtimeState.strategy ?: rootStrategy
        val cleaned = strategy.release(fastCleanup = false, preserveTproxyGuard = preserveTproxyGuard)
        rootRuntimeKnownClean = cleaned && strategy.managesSystemRouting && !preserveTproxyGuard
        if (rootRuntimeKnownClean) cleanup.recordKnownCleanState()
        return cleaned
    }

    private fun selectPersistedPhysicalRoute(state: XrayState): TunManager.PhysicalRoute? = state.physicalInterface
        ?.takeIf { it.isNotBlank() && it != VPN_SERVICE_INTERFACE_LABEL }
        ?.let { physicalInterface ->
            TunManager.PhysicalRoute(
                dev = physicalInterface,
                gateway = state.physicalGateway,
                table = state.physicalTable,
            )
        }

    private suspend fun <T> executeStep(step: ConnectionStep<T>): T = stepExecutor.execute(step)
}

private sealed interface XrayRuntimeState {
    val strategy: XrayRuntimeStrategy?

    data object Inactive : XrayRuntimeState {
        override val strategy: XrayRuntimeStrategy? = null
    }

    data class Starting(
        override val strategy: XrayRuntimeStrategy,
    ) : XrayRuntimeState

    data class Active(
        override val strategy: XrayRuntimeStrategy,
        val pid: Int,
        val tunName: String,
        val apiEndpoint: XrayApiEndpoint,
        val physicalRoute: TunManager.PhysicalRoute?,
    ) : XrayRuntimeState
}

private data class TproxyPlanPreparation(val plan: TproxyTrafficPlan?)

private fun effectiveRootBackend(
    managesSystemRouting: Boolean,
    configuredBackend: RootConnectionBackend,
): RootConnectionBackend = if (managesSystemRouting) configuredBackend else RootConnectionBackend.Tun

private const val LEGACY_DEFAULT_TUN_NAME = "xray0"
internal const val TPROXY_INTERFACE_LABEL = "TPROXY"
private const val CONNECTION_STEP_MAX_RETRIES = 2
private const val CONNECTION_STEP_RETRY_DELAY_MS = 1_500L
private const val XRAY_API_READY_TIMEOUT_MS = 10_000L
private const val XRAY_API_READY_RETRY_DELAY_MS = 100L
