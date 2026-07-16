package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun `batch append publishes ordered entries and retains the configured tail`() {
        val buffer = LogBuffer()

        buffer.appendAll(LogSource.XRAY, (0 until 2_500).map { "line-$it" })

        val entries = buffer.entries.value
        assertEquals(2_000, entries.size)
        assertEquals("line-500", entries.first().message)
        assertEquals("line-2499", entries.last().message)
        assertEquals((500L..2_499L).toList(), entries.map { it.id })
    }

    @Test
    fun `source clear preserves diagnostic entries from other sources`() {
        val buffer = LogBuffer()
        buffer.append(LogSource.APP, "recovery reason")
        buffer.append(LogSource.XRAY, "old core output")

        buffer.clear(LogSource.XRAY)

        assertEquals(listOf("recovery reason"), buffer.entries.value.map { it.message })
    }
}
