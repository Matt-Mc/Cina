package dev.pocketmuse

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class GoogleServiceTest {
    @Test fun pathAndQueryCannotChangeHostOrInjectParameters() {
        val operation = GoogleService.operations.single { it.service == "calendar" && it.name == "list_events" }
        val request = operation.request(JSONObject().put("calendarId", "a/b?x=1#@evil.example").put("pageToken", "token&access_token=oops"))
        assertEquals("www.googleapis.com", URI(request.url).host)
        assertTrue(request.url.contains("a%2Fb%3Fx%3D1%23%40evil.example"))
        assertTrue(request.url.endsWith("pageToken=token%26access_token%3Doops"))
        assertNull(request.body)
    }
    @Test fun arbitraryUrlsAndMissingIdsAreRejected() {
        val operation = GoogleService.operations.single { it.name == "get_document" }
        assertTrue(runCatching { operation.request(JSONObject().put("url", "https://evil.example")) }.isFailure)
        assertTrue(runCatching { operation.request(JSONObject().put("id", "..")) }.isFailure)
        assertTrue(runCatching { operation.request(JSONObject()) }.isFailure)
    }
    @Test fun writesRequireBodiesAndPreserveExactApprovedValues() {
        val operation = GoogleService.operations.single { it.name == "set_values" }
        assertTrue(runCatching { operation.request(JSONObject().put("id", "sheet").put("range", "A1")) }.isFailure)
        val body = JSONObject("""{"values":[["=literal","hello"]]}""")
        val request = operation.request(JSONObject().put("id", "sheet").put("range", "Sheet 1!A1:B1").put("body", body))
        assertEquals("PUT", operation.method)
        assertTrue(request.url.endsWith("?valueInputOption=RAW"))
        assertEquals(body.toString(), request.body.toString())
    }
    @Test fun permissionsAreIsolatedByService() {
        assertFalse(GoogleService.supports("custom_google"))
        assertTrue(GoogleService.scopes("contacts").any { it.endsWith("contacts.readonly") })
        assertFalse(GoogleService.scopes("contacts").any { it.contains("gmail") })
        assertFalse(GoogleService.scopes("drive").any { it.endsWith("/drive") })
        assertTrue(runCatching { GoogleService.scopes("unknown") }.isFailure)
    }
    @Test fun everyToolUsesHttpsAndHasUniqueServiceName() {
        val operations = GoogleService.operations
        assertEquals(operations.size, operations.map { it.service to it.name }.toSet().size)
        operations.forEach { operation ->
            assertTrue(operation.endpoint.startsWith("https://"))
            assertTrue(GoogleService.supports(operation.service))
            assertFalse(operation.schema().getBoolean("additionalProperties"))
            assertEquals(operation.method != "GET", operation.bodyDescription != null)
        }
    }
}
