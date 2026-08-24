package com.material.xray.data.repository

import com.material.xray.data.db.entity.AppRouteMode
import com.material.xray.model.BackupData
import com.material.xray.model.Protocol
import com.material.xray.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupImportPlanTest {
    @Test
    fun `version 3 preserves the complete relationship graph`() {
        val backup = BackupData(
            version = BackupData.STABLE_RELATIONSHIP_KEYS_VERSION,
            subscriptions = listOf(
                BackupData.BackupSubscription(
                    key = "subscription-4",
                    name = "Provider",
                    url = "https://example.com/sub",
                ),
            ),
            servers = listOf(
                BackupData.BackupServer(
                    key = "server-9",
                    subscriptionKey = "subscription-4",
                    subscriptionUrl = "https://example.com/sub",
                    config = ServerConfig(
                        protocol = Protocol.VLESS,
                        name = "Server",
                        address = "example.com",
                        port = 443,
                        password = "uuid",
                    ),
                ),
            ),
            bypassedApps = emptyList(),
            settings = mapOf("last_server_id" to "9"),
            appRoutes = listOf(
                BackupData.BackupAppRoute(
                    packageName = "com.example.app",
                    profileId = 10,
                    mode = AppRouteMode.Server.name,
                    serverKey = "server-9",
                ),
            ),
            selectedServerKey = "server-9",
        )

        val plan = BackupImportPlanner.create(backup)

        assertEquals("subscription-4", plan.servers.single().subscriptionKey)
        assertEquals(AppRouteMode.Server, plan.appRoutes.single().mode)
        assertEquals("server-9", plan.appRoutes.single().serverKey)
        assertEquals("server-9", plan.selectedServerKey)
    }

    @Test
    fun `legacy bypass entries remain importable`() {
        val backup = BackupData(
            subscriptions = emptyList(),
            bypassedApps = listOf("3:com.example.app"),
            settings = emptyMap(),
        )

        val route = BackupImportPlanner.create(backup).appRoutes.single()

        assertEquals(3, route.profileId)
        assertEquals("com.example.app", route.packageName)
        assertEquals(AppRouteMode.Bypass, route.mode)
        assertNull(route.serverKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `current version rejects a route to an unknown server`() {
        BackupImportPlanner.create(
            BackupData(
                version = BackupData.CURRENT_VERSION,
                subscriptions = emptyList(),
                bypassedApps = emptyList(),
                settings = emptyMap(),
                appRoutes = listOf(
                    BackupData.BackupAppRoute(
                        packageName = "com.example.app",
                        profileId = 0,
                        mode = AppRouteMode.Server.name,
                        serverKey = "missing",
                    ),
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import planning rejects newer settings defaults revision`() {
        BackupImportPlanner.create(
            BackupData(
                version = BackupData.CURRENT_VERSION,
                subscriptions = emptyList(),
                bypassedApps = emptyList(),
                settings = mapOf(SETTINGS_DEFAULTS_REVISION.name to "99"),
            ),
        )
    }
}
