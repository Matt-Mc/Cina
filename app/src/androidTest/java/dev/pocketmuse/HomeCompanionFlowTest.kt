package dev.pocketmuse

import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
            compose.waitUntil(5000) { compose.onAllNodesWithText("Needs your attention").fetchSemanticsNodes().isNotEmpty() }
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
