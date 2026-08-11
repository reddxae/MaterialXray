package com.material.xray.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RootConnectionBackendTest {
    @Test
    fun `missing and invalid values use the TPROXY default`() {
        assertEquals(RootConnectionBackend.Tproxy, RootConnectionBackend.fromValue(null))
        assertEquals(RootConnectionBackend.Tproxy, RootConnectionBackend.fromValue("automatic"))
    }

    @Test
    fun `persisted values round trip`() {
        RootConnectionBackend.entries.forEach { backend ->
            assertEquals(backend, RootConnectionBackend.fromValue(backend.persistedValue))
        }
    }
}
