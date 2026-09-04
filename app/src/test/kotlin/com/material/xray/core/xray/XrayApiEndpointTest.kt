package com.material.xray.core.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XrayApiEndpointTest {
    @Test
    fun `cli address supports TCP and filesystem sockets`() {
        assertEquals("127.0.0.1:48123", XrayApiEndpoint.LoopbackTcp(48123).cliServerAddress())
        assertEquals("unix:///data/user/0/app/files/api.sock", XrayApiEndpoint.FileSystemUnixSocket("/data/user/0/app/files/api.sock").cliServerAddress())
    }

    @Test
    fun `cli address rejects abstract sockets`() {
        assertNull(XrayApiEndpoint.UnixSocket("api").cliServerAddress())
    }
}
