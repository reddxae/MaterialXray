package com.material.xray.service

import com.material.xray.core.app.AppInventorySnapshot
import com.material.xray.core.app.AppInventorySource
import com.material.xray.core.app.InstalledApp
import com.material.xray.core.xray.ServerAddressResolver
import com.material.xray.core.xray.TunManager
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.entity.AppBypassEntity
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutingPlannerTest {

    @Test
    fun `build creates direct default-selected and server-specific routes`() = runTest {
        val defaultServer = server("Default", "198.51.100.1")
        val serverSpecificConfig = server("Server route", "203.0.113.7")
        val serverRepository = ServerRepository(
            FakeServerDao(
                ServerEntity(
                    id = SERVER_ID,
                    subscriptionId = 1,
                    name = serverSpecificConfig.name,
                    protocol = serverSpecificConfig.protocol.name,
                    address = serverSpecificConfig.address,
                    port = serverSpecificConfig.port,
                    configJson = Json.encodeToString(serverSpecificConfig),
                ),
            ),
        )
        val planner = AppRoutingPlanner(
            appBypassDao = FakeAppBypassDao(
                listOf(
                    assignment("direct.app", uid = 1001, excluded = true),
                    assignment("default.selected", uid = 1002, excluded = false, routeMode = "default_selected"),
                    assignment("server.specific", uid = 1003, excluded = false, serverId = SERVER_ID),
                    assignment("default.outbound", uid = 1004, excluded = false, routeMode = "default_outbound"),
                    assignment("direct.mode", uid = 1006, excluded = false, routeMode = "direct"),
                ),
            ),
            serverRepository = serverRepository,
            appInventory = FakeAppInventory(
                apps = listOf(
                    app("direct.app", uid = 2001),
                    app("default.selected", uid = 2002),
                    app("server.specific", uid = 2003),
                    app("default.outbound", uid = 2004),
                    app("unassigned.app", uid = 2005),
                    app("direct.mode", uid = 2006),
                ),
            ),
            serverAddressResolver = ServerAddressResolver(),
            log = LogBuffer(),
        )

        val plan = planner.build(
            baseTunName = BASE_TUN,
            baseRouteTable = BASE_TABLE,
            includeProxyRoutes = true,
            defaultProxyServer = defaultServer,
        )

        assertEquals(setOf(2001, 2006), plan.directUids)
        assertEquals(listOf(Long.MIN_VALUE, SERVER_ID), plan.proxyServerIds)
        assertEquals(
            listOf(
                TunManager.AppTunRoute(TunManager.appTunName(BASE_TUN, 1), routeTable = 110, uids = setOf(2002, 2005)),
                TunManager.AppTunRoute(TunManager.appTunName(BASE_TUN, 2), routeTable = 111, uids = setOf(2003)),
            ),
            plan.tunRoutes,
        )
        assertEquals(setOf(0), plan.routeProfileIds)

        val defaultRoute = plan.proxyRoutes.single { it.inboundTag == "app-in-default-selected" }
        assertEquals(TunManager.appTunName(BASE_TUN, 1), defaultRoute.tunName)
        assertEquals("proxy", defaultRoute.outboundTag)
        assertEquals(defaultServer, defaultRoute.server)
        assertTrue(defaultRoute.applyRoutingRules)

        val serverRoute = plan.proxyRoutes.single { it.inboundTag == "app-in-$SERVER_ID" }
        assertEquals(TunManager.appTunName(BASE_TUN, 2), serverRoute.tunName)
        assertEquals("app-proxy-$SERVER_ID", serverRoute.outboundTag)
        assertEquals(serverSpecificConfig, serverRoute.server)
    }

    @Test
    fun `build omits proxy route configs when only live routing update is needed`() = runTest {
        val planner = AppRoutingPlanner(
            appBypassDao = FakeAppBypassDao(
                listOf(
                    assignment("server.specific", uid = 1003, excluded = false, serverId = SERVER_ID),
                ),
            ),
            serverRepository = ServerRepository(FakeServerDao()),
            appInventory = FakeAppInventory(apps = listOf(app("server.specific", uid = 2003))),
            serverAddressResolver = ServerAddressResolver(),
            log = LogBuffer(),
        )

        val plan = planner.build(
            baseTunName = BASE_TUN,
            baseRouteTable = BASE_TABLE,
            includeProxyRoutes = false,
        )

        assertEquals(listOf(SERVER_ID), plan.proxyServerIds)
        assertEquals(
            listOf(TunManager.AppTunRoute(TunManager.appTunName(BASE_TUN, 1), routeTable = 110, uids = setOf(2003))),
            plan.tunRoutes,
        )
        assertTrue(plan.proxyRoutes.isEmpty())
    }

    @Test
    fun `missing server route does not consume proxy route cap`() = runTest {
        val validServerIds = 2L..65L
        val planner = AppRoutingPlanner(
            appBypassDao = FakeAppBypassDao(
                listOf(assignment("missing.server", uid = 1001, excluded = false, serverId = 1L)) +
                    validServerIds.map { id ->
                        assignment("server.$id", uid = 1000 + id.toInt(), excluded = false, serverId = id)
                    },
            ),
            serverRepository = ServerRepository(
                FakeServerDao(
                    servers = validServerIds.map { id -> serverEntity(id, server("Server $id", "203.0.113.$id")) },
                ),
            ),
            appInventory = FakeAppInventory(
                apps = listOf(app("missing.server", uid = 1001)) +
                    validServerIds.map { id -> app("server.$id", uid = 1000 + id.toInt()) },
            ),
            serverAddressResolver = ServerAddressResolver(),
            log = LogBuffer(),
        )

        val plan = planner.build(
            baseTunName = BASE_TUN,
            baseRouteTable = BASE_TABLE,
            includeProxyRoutes = true,
        )

        assertEquals(validServerIds.toList(), plan.proxyServerIds)
        assertEquals(64, plan.tunRoutes.size)
        assertEquals(64, plan.proxyRoutes.size)
    }

    @Test
    fun `default selected route occupies one proxy route cap slot`() = runTest {
        val serverIds = 1L..64L
        val defaultServer = server("Default", "198.51.100.1")
        val planner = AppRoutingPlanner(
            appBypassDao = FakeAppBypassDao(
                listOf(assignment("default.selected", uid = 1001, excluded = false, routeMode = "default_selected")) +
                    serverIds.map { id ->
                        assignment("server.$id", uid = 2000 + id.toInt(), excluded = false, serverId = id)
                    },
            ),
            serverRepository = ServerRepository(
                FakeServerDao(
                    servers = serverIds.map { id -> serverEntity(id, server("Server $id", "203.0.113.$id")) },
                ),
            ),
            appInventory = FakeAppInventory(
                apps = listOf(app("default.selected", uid = 1001)) +
                    serverIds.map { id -> app("server.$id", uid = 2000 + id.toInt()) },
            ),
            serverAddressResolver = ServerAddressResolver(),
            log = LogBuffer(),
        )

        val plan = planner.build(
            baseTunName = BASE_TUN,
            baseRouteTable = BASE_TABLE,
            includeProxyRoutes = true,
            defaultProxyServer = defaultServer,
        )

        assertEquals(listOf(Long.MIN_VALUE) + (1L..63L).toList(), plan.proxyServerIds)
        assertEquals(64, plan.tunRoutes.size)
        assertEquals(64, plan.proxyRoutes.size)
        assertTrue(plan.proxyRoutes.none { it.inboundTag == "app-in-64" })
    }

    private class FakeAppBypassDao(
        private val assignments: List<AppBypassEntity>,
    ) : AppBypassDao {
        override fun observeAll(): Flow<List<AppBypassEntity>> = flowOf(assignments)
        override suspend fun getAll(): List<AppBypassEntity> = assignments
        override suspend fun getExcluded(): List<AppBypassEntity> = assignments.filter { it.excluded }
        override suspend fun getProxyAssignments(): List<AppBypassEntity> = assignments.filter { !it.excluded && it.serverId != null }

        override suspend fun getDefaultProxyAssignments(): List<AppBypassEntity> = assignments.filter { !it.excluded && it.serverId == null && it.routeMode != "default_outbound" }

        override suspend fun upsert(entity: AppBypassEntity) = Unit
        override suspend fun updateServerId(oldServerId: Long, newServerId: Long) = Unit
        override suspend fun delete(profileId: Int, packageName: String) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun insertAll(entities: List<AppBypassEntity>) = Unit
    }

    private class FakeServerDao(
        server: ServerEntity? = null,
        private val servers: List<ServerEntity> = server?.let(::listOf).orEmpty(),
    ) : ServerDao {
        override fun observeAll(): Flow<List<ServerEntity>> = flowOf(servers)
        override fun observeBySubscription(subId: Long): Flow<List<ServerEntity>> = observeAll()
        override suspend fun getBySubscription(subId: Long): List<ServerEntity> = servers
        override suspend fun getById(id: Long): ServerEntity? = servers.firstOrNull { it.id == id }
        override suspend fun getAll(): List<ServerEntity> = servers
        override suspend fun insertAll(servers: List<ServerEntity>): List<Long> = servers.map { it.id }
        override suspend fun deleteBySubscription(subId: Long) = Unit
        override suspend fun updateLatency(id: Long, latency: Int) = Unit
        override suspend fun updateSortOrder(id: Long, sortOrder: Int) = Unit
        override suspend fun deleteAll() = Unit
    }

    private class FakeAppInventory(
        private val apps: List<InstalledApp>,
    ) : AppInventorySource {
        override suspend fun loadSnapshot(): AppInventorySnapshot = AppInventorySnapshot(apps = apps, profileIds = setOf(0))
    }

    private companion object {
        const val BASE_TUN = "xray"
        const val BASE_TABLE = 100
        const val SERVER_ID = 7L

        fun assignment(
            packageName: String,
            uid: Int,
            excluded: Boolean,
            serverId: Long? = null,
            routeMode: String? = null,
        ) = AppBypassEntity(
            packageName = packageName,
            uid = uid,
            excluded = excluded,
            serverId = serverId,
            routeMode = routeMode,
        )

        fun app(packageName: String, uid: Int) = InstalledApp(
            appKey = "0:$packageName",
            packageName = packageName,
            name = packageName,
            uid = uid,
            icon = null,
            systemApp = false,
            profileId = 0,
            workProfile = false,
        )

        fun server(name: String, address: String) = ServerConfig(
            protocol = Protocol.VLESS,
            name = name,
            address = address,
            port = 443,
            password = "uuid",
            transport = ServerConfig.Transport(type = "tcp"),
            security = ServerConfig.Security(type = "none"),
        )

        fun serverEntity(id: Long, config: ServerConfig) = ServerEntity(
            id = id,
            subscriptionId = 1,
            name = config.name,
            protocol = config.protocol.name,
            address = config.address,
            port = config.port,
            configJson = Json.encodeToString(config),
        )
    }
}
