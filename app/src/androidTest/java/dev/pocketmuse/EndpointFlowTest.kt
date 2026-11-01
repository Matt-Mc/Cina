package dev.pocketmuse

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EndpointFlowTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun downloadedLookupNeedsNoModelAndDoesNotContactSelectedEndpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        val old = vm.endpoint.value; val key = vm.secrets.get("inference_endpoint_key").orEmpty()
        val originalChat = vm.active.value; var testChat = 0L
        val id = "endpoint-offline-fixture"
        try {
            CollectionRepository(context).use { it.install(id, "Endpoint fixture", GeoPoint(45.0,-75.0),1,
                EssentialsSnapshot("2026-10-31T12:00:00Z",listOf(EssentialPlace("node/2","pharmacy","Offline pharmacy",GeoPoint(45.001,-75.0),"{}"))),100) }
            compose.runOnIdle {
                vm.newChat();testChat=vm.active.value
                vm.saveEndpoint("http://127.0.0.1:1","unreachable-model","")
                assertTrue(vm.send("Where is the nearest pharmacy in Endpoint fixture?"))
                assertFalse(vm.busy.value)
                assertTrue(vm.messages.value.last().body.contains("Offline pharmacy"))
                assertEquals("offline-essentials",vm.turns.value.last().modelPath)
            }
        } finally {
            compose.runOnIdle {
                if(old.url.isBlank()) vm.removeEndpoint() else { vm.saveEndpoint(old.url,old.model,key);vm.useEndpoint(old.enabled) }
                vm.openChat(originalChat)
            }
            CollectionRepository(context).use { it.delete(id) }
            if(testChat!=0L) LocalStore(context).use { it.deleteChat(testChat,false) }
        }
    }

    @Test fun directEndpointRepliesPersistAndPhoneSelectionDisablesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        val old = vm.endpoint.value
        val key = vm.secrets.get("inference_endpoint_key").orEmpty()
        val originalChat = vm.active.value
        var testChat = 0L
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildList { while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; add(line) } }
                        val count = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val chars = CharArray(count); var offset = 0
                        while (offset < count) { val n = reader.read(chars, offset, count-offset); check(n > 0); offset += n }
                        val body = """{"choices":[{"message":{"role":"assistant","content":"Endpoint flow verified"}}]}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        headers.joinToString("\n") + "\n" + chars.concatToString()
                    }
                }
                compose.runOnIdle {
                    vm.newChat(); testChat = vm.active.value
                    vm.saveEndpoint("http://127.0.0.1:${server.localPort}", "test-model", "fixture-secret")
                    assertTrue(vm.endpoint.value.enabled)
                    assertTrue(vm.send("Say hello"))
                }
                compose.waitUntil(15_000) { !vm.busy.value }
                val wire = request.get(5, TimeUnit.SECONDS)
                assertTrue(wire.startsWith("POST /v1/chat/completions"))
                assertTrue(wire.contains("Authorization: Bearer fixture-secret"))
                assertTrue(wire.contains("Say hello"))
                assertFalse(wire.contains("\"tools\""))
                LocalStore(context).use { store ->
                    assertTrue(store.messages(testChat).any { it.body == "Endpoint flow verified" })
                    assertEquals("completed", store.turns(testChat).last().status)
                }
                compose.activityRule.scenario.recreate()
                compose.onNodeWithText("Endpoint flow verified").assertExists()
                compose.runOnIdle { vm.useEndpoint(false); assertFalse(vm.endpoint.value.enabled) }
            } finally {
                executor.shutdownNow()
                compose.runOnIdle {
                    vm.stop()
                    if (old.url.isBlank()) vm.removeEndpoint() else { vm.saveEndpoint(old.url, old.model, key); vm.useEndpoint(old.enabled) }
                    vm.openChat(originalChat)
                }
                if (testChat != 0L) LocalStore(context).use { it.deleteChat(testChat, false) }
            }
        }
    }

    @Test fun settingsAndWorkExposeTheSupportedFeatures() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Skills").assertDoesNotExist()
        compose.onNodeWithText("Model server").performScrollTo().performClick()
        compose.onNodeWithText("Server URL").assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null),"endpoint-settings.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithText("Connect & load models").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("All work & filters").performClick()
        compose.onNodeWithText("Goals").assertDoesNotExist()
        compose.onNodeWithText("Scheduled").performClick()
        compose.onNodeWithText("+ Schedule").assertExists()
    }
}
