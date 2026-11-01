package dev.pocketmuse

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerSetupUiTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null),"cleanup-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun connectSelectSaveAndSwitchBackThroughTheScreen() {
        val vm=ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        val old=vm.endpoint.value
        val key=vm.secrets.get("inference_endpoint_key").orEmpty()
        ServerSocket(0).use { server ->
            server.soTimeout=15_000
            val executor=Executors.newSingleThreadExecutor()
            try {
                compose.runOnIdle { vm.removeEndpoint() }
                if(vm.selectedModel.value==null) compose.onNodeWithText("Set up phone model").assertIsDisplayed()
                capture("chat")
                compose.onNodeWithContentDescription("Open menu").performClick()
                compose.onNodeWithText("Settings").performScrollTo().performClick()
                capture("settings")
                compose.onNodeWithText("Model server").performScrollTo().performClick()
                compose.onNodeWithText("Server URL").performTextInput("http://127.0.0.1:${server.localPort}")
                closeSoftKeyboard()
                val request=executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout=5_000
                        val reader=socket.getInputStream().bufferedReader()
                        val first=reader.readLine()
                        while(!reader.readLine().isNullOrEmpty()) { }
                        val body="""{"data":[{"id":"local-test-model"}]}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        first
                    }
                }
                compose.onNodeWithText("Connect & load models").performScrollTo().performClick()
                compose.waitUntil(15_000) { vm.endpoint.value.models.isNotEmpty() }
                assertTrue(request.get(5,TimeUnit.SECONDS).startsWith("GET /v1/models"))
                compose.onNodeWithText("local-test-model").performScrollTo().performClick()
                compose.onNodeWithText("Save & use server").performScrollTo().assertIsEnabled().performClick()
                compose.runOnIdle { assertTrue(vm.endpoint.value.enabled);assertEquals("local-test-model",vm.endpoint.value.model) }
                compose.onNodeWithText("Switch to phone model").performScrollTo().performClick()
                compose.runOnIdle { assertFalse(vm.endpoint.value.enabled) }
                compose.onNodeWithText("Connection help").performScrollTo().performClick()
                compose.onNodeWithText("Ollama setup guide").performScrollTo().assertIsDisplayed()
                capture("ollama-help")
                compose.onNodeWithText("llama.cpp",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("llama.cpp server guide").performScrollTo().assertIsDisplayed()
                capture("llama-help")
                compose.onNodeWithText("Server URL").performScrollTo().performTextReplacement("http://127.0.0.1:1")
                closeSoftKeyboard()
                compose.runOnIdle { assertTrue(vm.endpoint.value.models.isEmpty()) }
                compose.onNodeWithText("Connect & load models").performScrollTo().performClick()
                compose.waitUntil(15_000) { vm.endpoint.value.error }
                compose.onNodeWithText("Could not connect.",substring=true).performScrollTo().assertIsDisplayed()
                capture("server-error")
            } finally {
                executor.shutdownNow()
                compose.runOnIdle {
                    if(old.url.isBlank()) vm.removeEndpoint() else { vm.saveEndpoint(old.url,old.model,key);vm.useEndpoint(old.enabled) }
                }
            }
        }
    }
}
