package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupManagerTest {
    @Test
    fun `owned process cleanup is valid shell syntax`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", persistedPid = 42)

        assertEquals(0, ProcessBuilder("sh", "-n", "-c", command).start().waitFor())
    }

    @Test
    fun `cleanup verifies config ownership before signaling candidate pids`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", persistedPid = 42)

        assertTrue(command.indexOf("kill \"\$pid\"") > command.indexOf("is_owned()"))
        assertTrue(command.contains("/proc/\$1/cmdline"))
        assertTrue(command.contains("cat -v \"/proc/\$1/cmdline\""))
        assertFalse(command.contains("tr "))
        assertTrue(command.contains("*\"\$config\"*"))
        assertTrue(command.contains("candidates='42'"))
        assertFalse(command.contains("return 2"))
        assertTrue(command.contains("if is_owned \"\$pid\"; then kill"))
        assertTrue(command.contains("if is_owned \"\$pid\"; then kill -9"))
    }

    @Test
    fun `cleanup never signals the persisted pid directly`() {
        val command = ownedProcessStopCommand("/data/user/0/app/files/config.json", persistedPid = 42)

        assertFalse(command.contains("kill 42"))
        assertFalse(command.contains("kill -9 42"))
    }
}
