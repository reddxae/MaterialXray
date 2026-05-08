package com.material.xray.service

import com.material.xray.core.xray.TunManager
import com.material.xray.core.xray.XrayState
import com.material.xray.model.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveRoutingUpdaterTest {

    @Test
    fun `applyAppRoutingChanges updates routing and persists physical route`() = runTest {
        val stateStore = FakeStateStore(
            XrayState(
                xrayPid = CORE_PID,
                tunName = BASE_TUN,
                fwmark = FWMARK,
                routeTable = ROUTE_TABLE,
                appProxyServerIds = listOf(PROXY_SERVER_ID),
            )
        )
        val plan = AppRoutingPlan(
            directUids = setOf(DIRECT_UID),
            proxyRoutes = emptyList(),
            tunRoutes = listOf(TunManager.AppTunRoute("xray-app1", routeTable = 201, uids = setOf(4001))),
            proxyServerIds = listOf(PROXY_SERVER_ID),
            routeProfileIds = setOf(0),
        )
        val tunGateway = FakeTunGateway()
        val updater = updater(
            stateStore = stateStore,
            routingPlanBuilder = FakeRoutingPlanBuilder(plan),
            tunGateway = tunGateway,
        )

        val applied = updater.applyAppRoutingChanges(connectedState(), BASE_TUN, FWMARK, ROUTE_TABLE)

        assertTrue(applied)
        assertEquals(setOf(DIRECT_UID, APP_UID), tunGateway.lastBypassUids)
        assertEquals(plan.tunRoutes, tunGateway.lastAppTunRoutes)
        assertEquals(plan.routeProfileIds, tunGateway.lastRouteProfileIds)
        assertEquals(1, tunGateway.lastManagedAppRouteCount)
        assertEquals("wlan0", stateStore.state?.physicalInterface)
        assertEquals("10.0.0.1", stateStore.state?.physicalGateway)
        assertEquals("main", stateStore.state?.physicalTable)
        assertTrue(stateStore.state?.ipRulesApplied == true)
    }

    @Test
    fun `applyAppRoutingChanges refuses stale persisted routing settings`() = runTest {
        val stateStore = FakeStateStore(
            XrayState(
                xrayPid = CORE_PID,
                tunName = "oldTun",
                fwmark = FWMARK,
                routeTable = ROUTE_TABLE,
                appProxyServerIds = listOf(PROXY_SERVER_ID),
            )
        )
        val tunGateway = FakeTunGateway()
        val updater = updater(
            stateStore = stateStore,
            routingPlanBuilder = FakeRoutingPlanBuilder(emptyPlan()),
            tunGateway = tunGateway,
        )

        val applied = updater.applyAppRoutingChanges(connectedState(), BASE_TUN, FWMARK, ROUTE_TABLE)

        assertFalse(applied)
        assertEquals(0, tunGateway.applyRoutingCalls)
    }

    @Test
    fun `reapplyPhysicalRoutingForNetworkChange reports route unavailable without rewriting state`() = runTest {
        val originalState = XrayState(
            xrayPid = CORE_PID,
            tunName = BASE_TUN,
            fwmark = FWMARK,
            routeTable = ROUTE_TABLE,
            appProxyServerIds = listOf(PROXY_SERVER_ID),
            physicalInterface = "rmnet0",
        )
        val stateStore = FakeStateStore(originalState)
        val result = updater(
            stateStore = stateStore,
            routingPlanBuilder = FakeRoutingPlanBuilder(emptyPlan(proxyServerIds = listOf(PROXY_SERVER_ID))),
            tunGateway = FakeTunGateway(physicalRoute = null),
        ).reapplyPhysicalRoutingForNetworkChange(connectedState(), BASE_TUN, FWMARK, ROUTE_TABLE)

        assertEquals(PhysicalRouteUpdateResult.RouteUnavailable, result)
        assertEquals(originalState, stateStore.state)
    }

    private fun updater(
        stateStore: FakeStateStore,
        routingPlanBuilder: RoutingPlanBuilder,
        tunGateway: FakeTunGateway = FakeTunGateway(),
        processProbe: XrayProcessProbe = FakeProcessProbe(alive = true),
    ) = ActiveRoutingUpdater(
        appUidProvider = { APP_UID },
        tunGateway = tunGateway,
        stateStore = stateStore,
        routingPlanBuilder = routingPlanBuilder,
        processProbe = processProbe,
        log = LogBuffer(),
        elapsedRealtime = {
            fakeClock += 10
            fakeClock
        },
    )

    private class FakeStateStore(
        var state: XrayState?,
    ) : ActiveRoutingStateStore {
        override fun read(): XrayState? = state

        override fun write(state: XrayState) {
            this.state = state
        }
    }

    private class FakeRoutingPlanBuilder(
        private val plan: AppRoutingPlan,
    ) : RoutingPlanBuilder {
        override suspend fun build(
            baseTunName: String,
            baseRouteTable: Int,
            includeProxyRoutes: Boolean,
            includeTunRoutes: Boolean,
            defaultProxyServer: com.material.xray.model.ServerConfig?,
        ): AppRoutingPlan = plan
    }

    private class FakeProcessProbe(
        private val alive: Boolean,
    ) : XrayProcessProbe {
        override suspend fun isAlive(pid: Int): Boolean = alive
    }

    private class FakeTunGateway(
        private val physicalRoute: TunManager.PhysicalRoute? = TunManager.PhysicalRoute(
            dev = "wlan0",
            gateway = "10.0.0.1",
            table = "main",
        ),
    ) : TunRoutingGateway {
        var applyRoutingCalls = 0
        var lastBypassUids: Set<Int> = emptySet()
        var lastAppTunRoutes: List<TunManager.AppTunRoute> = emptyList()
        var lastManagedAppRouteCount: Int? = null
        var lastRouteProfileIds: Set<Int> = emptySet()

        override suspend fun detectPhysicalRoute(tunName: String): TunManager.PhysicalRoute? =
            physicalRoute

        override suspend fun configureTun(
            tunName: String,
            addressCidr: String,
            isProcessAlive: suspend () -> Boolean,
        ): TunManager.TunSetupResult = TunManager.TunSetupResult(success = isProcessAlive())

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
            applyRoutingCalls += 1
            lastBypassUids = bypassUids
            lastAppTunRoutes = appTunRoutes
            lastManagedAppRouteCount = managedAppRouteCount
            lastRouteProfileIds = routeProfileIds
            return TunManager.RoutingResult(success = true)
        }
    }

    private companion object {
        const val CORE_PID = 42
        const val BASE_TUN = "xray0"
        const val FWMARK = 255
        const val ROUTE_TABLE = 100
        const val PROXY_SERVER_ID = 7L
        const val DIRECT_UID = 3001
        const val APP_UID = 12345

        var fakeClock = 0L

        fun connectedState() = ConnectionState.Connected(
            serverName = "server",
            corePid = CORE_PID,
            tunName = BASE_TUN,
            physicalInterface = "wlan0",
        )

        fun emptyPlan(proxyServerIds: List<Long> = emptyList()) = AppRoutingPlan(
            directUids = emptySet(),
            proxyRoutes = emptyList(),
            tunRoutes = emptyList(),
            proxyServerIds = proxyServerIds,
            routeProfileIds = setOf(0),
        )
    }
}
