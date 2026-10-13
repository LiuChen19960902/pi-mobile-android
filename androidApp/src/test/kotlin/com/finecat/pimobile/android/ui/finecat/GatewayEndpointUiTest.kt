package com.finecat.pimobile.android.ui.finecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayEndpointUiTest {
    @Test fun shortAuthorityRoundTripsWithoutRepeatedUrlParts() {
        val endpoint = normalizeGatewayEndpoint(" 10.23.45.67:3081 ")
        assertEquals("ws://10.23.45.67:3081/ws/mobile", endpoint)
        assertEquals("10.23.45.67:3081", gatewayAddressLabel(endpoint))
        assertEquals("ws://home.tailnet.ts.net:3081/ws/mobile", normalizeGatewayEndpoint("home.tailnet.ts.net:3081"))
        assertEquals("[::1]:3081", gatewayAddressLabel(normalizeGatewayEndpoint("[::1]:3081")))
    }
    @Test fun fullSecureLegacyUrlIsPreserved() {
        val value = "wss://gateway.example:443/custom/path?mode=mobile"
        assertEquals(value, normalizeGatewayEndpoint(value))
        assertEquals("gateway.example:443", gatewayAddressLabel(value))
        assertEquals("10.23.45.67:3081", gatewayAddressLabel("10.23.45.67:3081"))
    }
    @Test fun invalidInputsCannotBecomeSavedEndpoints() {
        listOf("", "127.0.0.1", "localhost:0", "localhost:65536", "localhost:x", "host:12/other",
            "host:12?x=1", "https://host:12/ws/mobile", "user:secret@host:12", "a b:12", "host:12#fragment")
            .forEach { assertNull(it, normalizeGatewayEndpoint(it)) }
    }
}
