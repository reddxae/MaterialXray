package com.material.xray.core.xray

import com.material.xray.model.ActiveBalancerSelection
import com.material.xray.model.BalancerOutbound
import com.xray.app.observatory.HealthPingMeasurementResult
import com.xray.app.observatory.OutboundStatus
import com.xray.app.router.command.BalancerMsg
import com.xray.app.router.command.GetBalancerInfoResponse
import com.xray.app.router.command.OverrideInfo
import com.xray.app.router.command.PrincipleTargetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayRoutingClientTest {
    @Test
    fun `least load pool retains every selected server in stable order`() {
        val response = response(tags = listOf("proxy-3", "proxy-1", "", "proxy-2", "proxy-1", " "))

        assertEquals(listOf("proxy-1", "proxy-2", "proxy-3"), response.selectedOutboundTags())
    }

    @Test
    fun `override takes precedence over a multi server pool`() {
        val response = response(tags = listOf("proxy-1", "proxy-2"), override = "proxy-3")

        assertEquals(listOf("proxy-3"), response.selectedOutboundTags())
    }

    @Test
    fun `blank override leaves the selected pool intact`() {
        assertEquals(listOf("proxy-1"), response(listOf("proxy-1"), override = " ").selectedOutboundTags())
    }

    @Test
    fun `empty response has no selected servers`() {
        assertEquals(emptyList<String>(), GetBalancerInfoResponse.getDefaultInstance().selectedOutboundTags())
    }

    @Test
    fun `burst observation uses average nanoseconds instead of regular delay`() {
        val status = OutboundStatus.newBuilder()
            .setAlive(true)
            .setDelay(999)
            .setHealthPing(
                HealthPingMeasurementResult.newBuilder().setAll(4).setFail(1).setAverage(45_900_000),
            )
            .build()

        assertEquals(45L, status.balancerLatencyMs())
    }

    @Test
    fun `unhealthy and entirely failed observations have no latency`() {
        val status = OutboundStatus.newBuilder().setDelay(45).build()
        assertNull(status.balancerLatencyMs())
        assertNull(
            status.toBuilder().setAlive(true)
                .setHealthPing(HealthPingMeasurementResult.newBuilder().setAll(4).setFail(4))
                .build().balancerLatencyMs(),
        )
    }

    @Test
    fun `regular observation retains milliseconds`() {
        assertEquals(45L, OutboundStatus.newBuilder().setAlive(true).setDelay(45).build().balancerLatencyMs())
    }

    @Test
    fun `pool ping averages every selected server and rounds to milliseconds`() {
        val server = BalancerOutbound("proxy-1", 45)
        assertEquals(45L, ActiveBalancerSelection(listOf(server)).latencyMs)
        assertEquals(58L, ActiveBalancerSelection(listOf(server, BalancerOutbound("proxy-2", 70))).latencyMs)
        assertNull(ActiveBalancerSelection().latencyMs)
    }

    @Test
    fun `pool ping stays unavailable when any selected server has no measurement`() {
        assertNull(ActiveBalancerSelection(listOf(BalancerOutbound("proxy-1", 45), BalancerOutbound("proxy-2", null))).latencyMs)
    }

    private fun response(tags: List<String>, override: String = ""): GetBalancerInfoResponse = GetBalancerInfoResponse.newBuilder()
        .setBalancer(
            BalancerMsg.newBuilder()
                .setOverride(OverrideInfo.newBuilder().setTarget(override))
                .setPrincipleTarget(PrincipleTargetInfo.newBuilder().addAllTag(tags)),
        )
        .build()
}
