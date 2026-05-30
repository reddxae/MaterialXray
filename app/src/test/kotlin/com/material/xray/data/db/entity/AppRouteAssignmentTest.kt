package com.material.xray.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRouteAssignmentTest {
    @Test
    fun routeAssignmentParsesPersistedModes() {
        assertEquals(AppRouteAssignment(AppRouteMode.Bypass), entity(excluded = true).routeAssignment())
        assertEquals(AppRouteAssignment(AppRouteMode.Direct), entity(routeMode = "direct").routeAssignment())
        assertEquals(
            AppRouteAssignment(AppRouteMode.DefaultOutbound),
            entity(routeMode = "default_outbound").routeAssignment(),
        )
        assertEquals(AppRouteAssignment(AppRouteMode.Server, 42), entity(serverId = 42).routeAssignment())
        assertEquals(
            AppRouteAssignment(AppRouteMode.DefaultSelected),
            entity(routeMode = "default_selected").routeAssignment(),
        )
        assertEquals(AppRouteAssignment(AppRouteMode.DefaultSelected), entity(routeMode = null).routeAssignment())
    }

    @Test
    fun toAppBypassEntityPreservesPersistedValues() {
        val server = AppRouteAssignment(AppRouteMode.Server, 42)
            .toAppBypassEntity(packageName = "pkg", profileId = 10, uid = 123, manual = true)

        assertFalse(server.excluded)
        assertEquals(42L, server.serverId)
        assertEquals("server", server.routeMode)

        val bypass = AppRouteAssignment(AppRouteMode.Bypass)
            .toAppBypassEntity(packageName = "pkg", profileId = 0, uid = 123, manual = false)

        assertTrue(bypass.excluded)
        assertNull(bypass.serverId)
        assertEquals("bypass", bypass.routeMode)
        assertFalse(bypass.manual)
    }

    @Test
    fun isManualRouteOverrideIgnoresDefaultSelectedAssignments() {
        assertTrue(entity(routeMode = "direct", manual = true).isManualRouteOverride())
        assertTrue(entity(serverId = 42, routeMode = "server", manual = true).isManualRouteOverride())

        assertFalse(entity(routeMode = "direct", manual = false).isManualRouteOverride())
        assertFalse(entity(routeMode = "default_selected", manual = true).isManualRouteOverride())
        assertFalse(entity(routeMode = null, manual = true).isManualRouteOverride())
    }

    private fun entity(
        excluded: Boolean = false,
        serverId: Long? = null,
        routeMode: String? = null,
        manual: Boolean = true,
    ): AppBypassEntity =
        AppBypassEntity(
            packageName = "pkg",
            uid = 123,
            excluded = excluded,
            serverId = serverId,
            routeMode = routeMode,
            manual = manual,
        )
}
