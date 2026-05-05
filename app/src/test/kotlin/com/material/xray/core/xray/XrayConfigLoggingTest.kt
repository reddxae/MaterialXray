package com.material.xray.core.xray

import com.material.xray.model.XrayLogLevel
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayConfigLoggingTest {

    @Test
    fun `buildLogConfig disables access log and uses selected log level`() {
        val config = buildLogConfig(XrayLogLevel.Warning)

        assertEquals("none", config.getValue("access").jsonPrimitive.content)
        assertEquals("warning", config.getValue("loglevel").jsonPrimitive.content)
    }
}
