package dev.pocketmuse

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WebPageReaderPolicyTest {
    @Test fun onlyPublicWebSchemesAndDefaultPortsAreAccepted() {
        listOf("file:///etc/passwd", "ftp://example.com", "https://user:pass@example.com", "http://localhost", "https://x.local/path", "https://example.com:8000").forEach {
            assertTrue(it, runCatching { WebPageReader.publicUrl(it) }.isFailure)
        }
        assertEquals("example.com", WebPageReader.publicUrl("https://example.com/path?q=test").host)
    }

    @Test fun localAndPrivateResolvedAddressesAreBlocked() {
        listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "192.168.1.2", "172.16.0.1", "169.254.169.254", "100.64.0.1", "224.0.0.1", "::1", "fe80::1", "fd00::1").forEach {
            assertFalse(it, WebPageReader.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(WebPageReader.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun readerIsReadOnlyAndUnavailableWhenWebIsOffOrScheduled() {
        assertEquals(ToolKind.READ, ToolCatalog.kind("web_read"))
        assertTrue(ToolCatalog.select("Check the schedule", web = true).any { it.name == "web_read" })
        assertFalse(ToolCatalog.select("Check the schedule", web = false, all = true).any { it.group == "web" })
        assertFalse(ToolCatalog.select("Check the schedule", web = true, scheduled = true, all = true).any { it.group == "web" })
    }
}
