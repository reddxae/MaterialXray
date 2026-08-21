package com.material.xray.data.repository

import com.material.xray.service.PendingRoutingChange
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingCoordinatorTest {

    @Test
    fun `provider-disabled selection does not read or apply routing`() = runTest {
        var applyCalls = 0
        val coordinator = coordinator(
            selection = ProviderRoutingSelection.NotProviderControlled,
            applyAppRouting = {
                applyCalls += 1
                true
            },
        )

        val result = coordinator.refreshSelectedServer()

        assertEquals(ProviderRoutingRefreshResult.NotProviderControlled, result)
        assertEquals(0, applyCalls)
    }

    @Test
    fun `selected subscription without provider routing preserves manual routing`() = runTest {
        var appRoutingApplyCalls = 0
        var xrayRoutingApplyCalls = 0
        val coordinator = coordinator(
            selection = ProviderRoutingSelection.Selected(
                subscriptionId = 7,
                appRoutingProvided = false,
                xrayRoutingProvided = false,
            ),
            applyAppRouting = {
                appRoutingApplyCalls += 1
                true
            },
            applyXrayRouting = {
                xrayRoutingApplyCalls += 1
                true
            },
        )

        val result = coordinator.refreshSelectedServer()

        assertEquals(ProviderRoutingRefreshResult.Unchanged, result)
        assertEquals(0, appRoutingApplyCalls)
        assertEquals(0, xrayRoutingApplyCalls)
    }

    @Test
    fun `app-only provider applies only app routing`() = runTest {
        var appRoutingApplyCalls = 0
        var xrayRoutingApplyCalls = 0
        val coordinator = coordinator(
            selection = ProviderRoutingSelection.Selected(
                subscriptionId = 7,
                appRoutingProvided = true,
                xrayRoutingProvided = false,
            ),
            applyAppRouting = {
                appRoutingApplyCalls += 1
                true
            },
            applyXrayRouting = {
                xrayRoutingApplyCalls += 1
                true
            },
        )

        val result = coordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)

        assertEquals(ProviderRoutingRefreshResult.Persisted(PendingRoutingChange.APP_ROUTING), result)
        assertEquals(1, appRoutingApplyCalls)
        assertEquals(0, xrayRoutingApplyCalls)
    }

    @Test
    fun `xray-only provider applies only xray routing`() = runTest {
        var appRoutingApplyCalls = 0
        var xrayRoutingApplyCalls = 0
        val coordinator = coordinator(
            selection = ProviderRoutingSelection.Selected(
                subscriptionId = 7,
                appRoutingProvided = false,
                xrayRoutingProvided = true,
            ),
            applyAppRouting = {
                appRoutingApplyCalls += 1
                true
            },
            applyXrayRouting = {
                xrayRoutingApplyCalls += 1
                true
            },
        )

        val result = coordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)

        assertEquals(ProviderRoutingRefreshResult.Persisted(PendingRoutingChange.XRAY_CONFIG), result)
        assertEquals(0, appRoutingApplyCalls)
        assertEquals(1, xrayRoutingApplyCalls)
    }

    @Test
    fun `xray configuration change takes precedence and requests active update`() = runTest {
        val requestedChanges = mutableListOf<PendingRoutingChange>()
        val coordinator = coordinator(
            applyAppRouting = { true },
            applyXrayRouting = { true },
            applyActiveConnectionChange = { change ->
                requestedChanges += change
                true
            },
        )

        val result = coordinator.refreshSelectedServer()

        assertEquals(
            ProviderRoutingRefreshResult.ActiveUpdateRequested(PendingRoutingChange.XRAY_CONFIG),
            result,
        )
        assertEquals(listOf(PendingRoutingChange.XRAY_CONFIG), requestedChanges)
    }

    @Test
    fun `deferred refresh persists app routing without touching active connection`() = runTest {
        var activeApplyCalls = 0
        val coordinator = coordinator(
            applyAppRouting = { true },
            applyActiveConnectionChange = {
                activeApplyCalls += 1
                true
            },
        )

        val result = coordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER)

        assertEquals(
            ProviderRoutingRefreshResult.Persisted(PendingRoutingChange.APP_ROUTING),
            result,
        )
        assertEquals(0, activeApplyCalls)
    }

    @Test
    fun `concurrent refreshes are serialized and the second observes unchanged routing`() = runTest {
        var inFlight = 0
        var maxInFlight = 0
        var applyCalls = 0
        val coordinator = coordinator(
            applyAppRouting = {
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
                delay(10)
                applyCalls += 1
                inFlight -= 1
                applyCalls == 1
            },
        )

        val results = awaitAll(
            async { coordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER) },
            async { coordinator.refreshSelectedServer(ProviderRoutingActiveUpdate.DEFER) },
        )

        assertEquals(1, maxInFlight)
        assertEquals(2, applyCalls)
        assertTrue(ProviderRoutingRefreshResult.Persisted(PendingRoutingChange.APP_ROUTING) in results)
        assertTrue(ProviderRoutingRefreshResult.Unchanged in results)
    }

    private fun coordinator(
        selection: ProviderRoutingSelection = ProviderRoutingSelection.Selected(subscriptionId = 7),
        applyAppRouting: suspend (Long) -> Boolean = { false },
        applyXrayRouting: suspend (Long) -> Boolean = { false },
        applyActiveConnectionChange: (PendingRoutingChange) -> Boolean = { false },
    ) = ProviderRoutingCoordinator(
        loadSelection = { selection },
        applyAppRouting = applyAppRouting,
        applyXrayRouting = applyXrayRouting,
        applyActiveConnectionChange = applyActiveConnectionChange,
    )
}
