package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test

class HomeClientTest {
    @Test fun automaticHomeRoutingOnlyUsesReachableDeepAnalysis() {
        assertEquals("home-model", HomeRouting.automatic(ResponseMode.DEEP, "Please compare these two approaches", true, "home-model"))
        assertNull(HomeRouting.automatic(ResponseMode.QUICK, "Compare these", true, "home-model"))
        assertNull(HomeRouting.automatic(ResponseMode.DEEP, "Compare these", false, "home-model"))
        assertNull(HomeRouting.automatic(ResponseMode.DEEP, "Compare these and send the result", true, "home-model"))
        assertNull(HomeRouting.automatic(ResponseMode.DEEP, "Create a detailed plan and save a note", true, "home-model"))
        assertNull(HomeRouting.automatic(ResponseMode.DEEP, "Compare these", true, null))
    }
    @Test fun onlyHttpsRootAddressesAndExplicitPinsAreAccepted() {
        assertEquals("https://192.168.1.20:8443", HomeClient.validUrl("https://192.168.1.20:8443/"))
        listOf("http://host:8443", "https://user:pass@host", "https://host/path", "https://host?token=secret", "https://host/#fragment", "https://host:0").forEach {
            assertTrue(it, runCatching { HomeClient.validUrl(it) }.isFailure)
        }
        assertEquals("ab".repeat(32), HomeClient.validPin("AB:".repeat(31) + "AB"))
        listOf("", "trust-all", "00".repeat(31), "gg".repeat(32)).forEach { assertTrue(runCatching { HomeClient.validPin(it) }.isFailure) }
    }
}
