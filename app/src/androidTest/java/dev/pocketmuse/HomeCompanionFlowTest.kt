package dev.pocketmuse

import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Opt-in end-to-end test against companion/integration_fixture.py, through adb reverse. */
class HomeCompanionFlowTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun pinnedPairingModelSelectionDurableAnswerAndWorkShareExistingScreens() {
        val encoded = InstrumentationRegistry.getArguments().getString("cinaHomeBundle")
        Assume.assumeTrue("Requires the local HTTPS fixture and cinaHomeBundle argument", encoded != null)
        val bundle = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        val config = JSONObject(bundle)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = HomeRepository(base)
        Assume.assumeTrue("Run on a disposable emulator without a real Home pairing", repo.credentials() == null)
        var chat: Long? = null
        val launchIntent = compose.activity.intent
        try {
            assertTrue(runCatching { HomeClient(config.getString("url"), "00".repeat(32)).call("/v1/state") }.isFailure)
            compose.onNodeWithContentDescription("Open menu").performClick(); compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Connections").performScrollTo().performClick()
            compose.onNodeWithText("+ Add").performClick()
            compose.onNodeWithText("Add your Home server").performScrollTo().performClick()
            compose.onNodeWithText("Pairing details").performTextInput(bundle)
            compose.onNodeWithText("Connect", useUnmergedTree = true).performClick()
            compose.waitUntil(15000) { repo.credentials() != null }
            runBlocking { repo.sync() }
            val vm = ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
            compose.runOnIdle { vm.refreshHome() }
            compose.waitUntil(15000) { vm.homeState.value.models.isNotEmpty() }
            assertEquals(config.getString("server_id"), HomeRepository(base).credentials()!!.serverId)
            compose.onNodeWithContentDescription("Open menu").performClick(); compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Models").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Choose Home model").performClick()
            compose.onNodeWithContentDescription("Download qwen3:8b on Home").performScrollTo()
            File(base.getExternalFilesDir(null), "home-model-download.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("Download qwen3:8b on Home").performClick()
            compose.waitUntil(20000) { vm.homeState.value.models.any { it.name == "qwen3:8b" } }
            assertTrue(repo.jobs().any { it.kind == "download" && it.model == "qwen3:8b" && it.state == "completed" })
            compose.onAllNodesWithText("Use")[1].performScrollTo().performClick()
            compose.waitUntil(5000) { vm.homeState.value.deepModel == "qwen3:8b" }
            assertTrue(vm.homeState.value.hubModels)
            val hubModel = "hf.co/Qwen/Qwen3-1.7B-GGUF:Qwen3-1.7B-Q8_0.gguf"
            compose.runOnIdle { vm.openRepo("Qwen/Qwen3-1.7B-GGUF") }
            compose.waitUntil(30000) { vm.modelFiles.value.repo == "Qwen/Qwen3-1.7B-GGUF" && !vm.modelFiles.value.loading }
            assertTrue(vm.modelFiles.value.error, vm.modelFiles.value.error.isBlank())
            compose.onNodeWithText("Download on Home").performScrollTo()
            File(base.getExternalFilesDir(null), "home-hub-download.png").outputStream().use { compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("Download on Home").performClick()
            compose.waitUntil(20000) { vm.homeState.value.models.any { it.name == hubModel } }
            assertTrue(repo.jobs().any { it.kind == "download" && it.model == hubModel && it.state == "completed" })
            assertFalse(vm.download.value.running)
            compose.onNodeWithContentDescription("Go back").performClick()
            compose.onNodeWithText("qwen3:8b").assertIsDisplayed()
            File(base.getExternalFilesDir(null), "model-choices-connected.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("Open menu").performClick(); compose.onNodeWithText("Chat").performClick()
            compose.onNodeWithContentDescription("New chat").performClick()
            compose.runOnIdle { chat = vm.active.value }
            // Open the existing model picker; no extra execution-location control is added.
            compose.onNodeWithContentDescription("Choose model").performClick()
            compose.onAllNodesWithText("Use")[0].performClick()
            compose.waitUntil(5000) { vm.homeState.value.selected == "qwen3:4b" }
            compose.onNodeWithContentDescription("Message Cina").performTextInput("Please analyse the test passage")
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(20000) { LocalStore(base).use { store -> store.messages(chat!!).any { it.body == "Verified fixture answer" } } }
            runBlocking { repeat(2) { repo.sync() } }
            LocalStore(base).use { store -> assertEquals(1, store.messages(chat!!).count { it.body == "Verified fixture answer" }) }
            compose.activityRule.scenario.recreate()
            HomeFollowUps.recover(base, repo.jobs())
            val id = repo.jobs().first { it.chat == chat }.id
            val manager = base.getSystemService(android.app.NotificationManager::class.java)
            manager.activeNotifications.single { it.tag == "home:$id" }.notification.contentIntent.send()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Needs you",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Please analyse the test passage").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Completed · Home · qwen3:4b").assertIsDisplayed()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.intent = launchIntent }
            runBlocking { if(repo.credentials() != null) repo.disconnect() }
            LocalStore(base).use { store ->
                store.writableDatabase.delete("home_jobs", "server=?", arrayOf(config.getString("server_id")))
                chat?.let { store.deleteChat(it, true) }
            }
        }
    }
}
