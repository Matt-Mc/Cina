package dev.pocketmuse

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WorkEditorUsabilityTest {
    @get:Rule val compose=createComposeRule()
    @Test fun changingStepTypeAndBackPreservesNoteContent() {
        compose.setContent { MaterialTheme { WorkTaskEditor(null,null,{}) { _,_-> } } }
        compose.onNodeWithText("Task title").performTextInput("Keep my writing")
        compose.onNodeWithText("Note content").performTextInput("Do not lose this note")
        closeSoftKeyboard()
        compose.onNodeWithText("Reminder",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Note",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Do not lose this note").assertExists()
        compose.onNodeWithText("Save task for review").assertIsEnabled()
    }
}

class UsabilityRefreshTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val marker="Ux${UUID.randomUUID().toString().take(8)}"
    private fun go(label: String) { compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithText(label).performClick() }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        // Dialog windows animate outside the Compose clock; wait before a visual-only capture.
        android.os.SystemClock.sleep(400)
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(base.getExternalFilesDir(null),"uiux-$name.png").outputStream().use { stream -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream) }
    }
    @Test fun librarySearchesNotesSourcesAndMemoryAndSourceBackReturnsToSearch() {
        val sourceId=UUID.randomUUID().toString()
        LocalStore(base).use { store ->
            store.addNote("$marker note","$marker note body")
            store.addMemory("$marker personal detail")
            InboxRepository(store).capture(sourceId,CapturedSource("$marker source","text/plain",marker.toByteArray(),marker,"Test"))
        }
        try {
            go("Library")
            compose.onNodeWithText("Search your library").performTextInput(marker)
            closeSoftKeyboard()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("$marker note").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("$marker note"))
            compose.onNodeWithText("$marker note").assertIsDisplayed()
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("$marker personal detail"))
            compose.onNodeWithText("$marker personal detail").assertIsDisplayed()
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("$marker source"))
            compose.onNodeWithText("$marker source").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Captured content").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Go back").performClick()
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("Search your library"))
            compose.onNodeWithText("Search your library").assertExists()
            compose.onNodeWithText(marker).assertExists()
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("$marker source"))
            compose.onNodeWithText("$marker source").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Captured content").fetchSemanticsNodes().isNotEmpty() }
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("Search your library"))
            compose.onNodeWithText("Search your library").assertExists()
            compose.onNodeWithText(marker).assertExists()
            capture("library")
        } finally {
            LocalStore(base).use { store -> store.notes().filter { it.title.startsWith(marker) }.forEach { store.deleteNote(it.id) };store.memories().filter { it.fact.startsWith(marker) }.forEach { store.deleteMemory(it.id) };InboxRepository(store).delete(sourceId) }
        }
    }
    @Test fun memoryDeleteRequiresConfirmation() {
        LocalStore(base).use { it.addMemory("$marker remember this") }
        try {
            go("Library")
            compose.onNodeWithText("Search your library").performTextInput(marker)
            closeSoftKeyboard()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("$marker remember this").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$marker remember this").performScrollTo().performClick()
            compose.onNodeWithText("Delete").performScrollTo().performClick()
            compose.onNodeWithText("Delete this personal detail?").assertIsDisplayed()
            LocalStore(base).use { assertTrue(it.memories().any { m -> m.fact=="$marker remember this" }) }
            compose.onNodeWithText("Keep").performClick()
            LocalStore(base).use { assertTrue(it.memories().any { m -> m.fact=="$marker remember this" }) }
        } finally { LocalStore(base).use { store -> store.memories().filter { it.fact.startsWith(marker) }.forEach { store.deleteMemory(it.id) } } }
    }
    @Test fun workOpensOnlyTheSelectedApprovalWithoutRunningIt() {
        val ids=LocalStore(base).use { store -> listOf(WorkCoordinator(store).save(WorkAction("note","$marker chosen","First")),WorkCoordinator(store).save(WorkAction("note","$marker other","Second"))) }
        try {
            go("Work")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("$marker chosen").fetchSemanticsNodes().isNotEmpty() }
            capture("work")
            compose.onNodeWithText("$marker chosen").performScrollTo().performClick()
            compose.onNodeWithText("Approve this local action?").assertIsDisplayed()
            compose.onAllNodesWithText("$marker other").assertCountEquals(0)
            LocalStore(base).use { store -> assertEquals("waiting_for_approval",WorkCoordinator(store).task(ids.first())!!.state);assertTrue(store.notes().none { it.title.startsWith(marker) }) }
            androidx.test.espresso.Espresso.pressBack()
            go("Chat");go("Work")
            compose.onNodeWithText("Needs you",substring=true).assertExists()
            compose.onNodeWithText("$marker other").assertExists()
        } finally {
            LocalStore(base).use { store -> ids.forEach { id -> listOf("work_steps","durable_work_events").forEach { store.writableDatabase.delete(it,"work_id=?",arrayOf(id)) };store.writableDatabase.delete("durable_work","id=?",arrayOf(id)) } }
        }
    }
    @Test fun noteDraftSurvivesClosingAndRecreatingActivity() {
        base.getSharedPreferences("note_draft",Context.MODE_PRIVATE).edit().clear().commit()
        go("Library")
        compose.onNodeWithText("New note").performClick()
        compose.onNodeWithText("Title").performTextInput(marker)
        compose.onNodeWithText("Note").performTextInput("$marker unfinished")
        closeSoftKeyboard()
        compose.onNodeWithText("Close").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Resume draft").performScrollTo().performClick()
        compose.onNodeWithText("$marker unfinished").assertExists()
        capture("note-draft")
        base.getSharedPreferences("note_draft",Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun modelConnectionBackReturnsToHomeChoiceAndChatDraftStays() {
        compose.onNodeWithContentDescription("Message Cina").performTextInput("$marker draft")
        closeSoftKeyboard()
        compose.onNodeWithText("Auto ⌄").performClick()
        compose.onNodeWithText("Manage models").performClick()
        // The guided first-run choice retains an explicit route to optional model management.
        if(compose.onAllNodesWithText("Browse other models").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Browse other models").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Go back").performClick()
        }
        compose.onNodeWithContentDescription("Choose Home model").performScrollTo().performClick()
        compose.onNodeWithText("Connect Home").performClick()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithText("Connect Home").assertExists()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithText("$marker draft").assertExists()
        compose.onNodeWithText("Get a recommended model").assertIsDisplayed()
        capture("chat")
    }
    @Test fun guidedSetupReturnsToDraftWhenPhoneModelBecomesReady() {
        val vm=ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        val file=File(base.filesDir,"$marker.gguf")
        val previous=vm.selectedModel.value
        try {
            compose.onNodeWithContentDescription("Message Cina").performTextInput("$marker setup draft")
            closeSoftKeyboard()
            compose.onNodeWithText("Get a recommended model").performClick()
            compose.onNodeWithText("Get Cina ready").assertExists()
            capture("setup")
            compose.onNodeWithText("Browse other models").performScrollTo().performClick()
            file.writeText("Navigation fixture only; no inference is requested")
            LocalStore(base).use { it.addModel(marker,file.path,"fixture",1_000_000_000) }
            compose.runOnIdle { vm.refresh();vm.setModel(file.path) }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Message Cina").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$marker setup draft").assertExists()
        } finally {
            LocalStore(base).use { it.writableDatabase.delete("models","path=?",arrayOf(file.path)) }
            file.delete()
            compose.runOnIdle { if(previous!=null) vm.setModel(previous);vm.refresh() }
        }
    }
    @Test fun incomingReplyDoesNotPullReaderAwayFromOlderMessages() {
        val vm=ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        compose.runOnIdle { vm.newChat() }
        val chat=vm.active.value
        try {
            LocalStore(base).use { store -> repeat(20) { store.addMessage(chat,"assistant","Earlier thought $it. "+"A little space to think. ".repeat(12)) } }
            compose.runOnIdle { vm.refresh() }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Conversation messages").fetchSemanticsNodes().isNotEmpty() }
            repeat(2) { compose.onNodeWithContentDescription("Conversation messages").performTouchInput { swipeDown() } }
            capture("reading-position")
            compose.onNodeWithText("Latest messages ↓").assertIsDisplayed()
            LocalStore(base).use { it.addMessage(chat,"assistant","$marker new reply") }
            compose.runOnIdle { vm.refresh() }
            compose.onNodeWithText("New reply ↓").assertIsDisplayed().performClick()
            compose.onNodeWithText("$marker new reply").assertIsDisplayed()
        } finally { compose.runOnIdle { vm.deleteChat(chat,false) } }
    }
}
