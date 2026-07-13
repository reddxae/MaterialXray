package com.material.xray.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateInstallerTest {
    @Test
    fun rootInstallCommandQuotesPathsAndPreservesInstallResult() {
        val command = rootInstallCommand("/data/user/0/com.material.xray/cache/update's.apk")

        assertEquals(
            "cp '/data/user/0/com.material.xray/cache/update'\\''s.apk' " +
                "'/data/local/tmp/MaterialXray-update.apk' && " +
                "chmod 0644 '/data/local/tmp/MaterialXray-update.apk' && " +
                "pm install -r '/data/local/tmp/MaterialXray-update.apk'; " +
                "status=\$?; rm -f '/data/local/tmp/MaterialXray-update.apk'; test \$status -eq 0",
            command,
        )
    }
}
