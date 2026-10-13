package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UsabilityPersistenceTest {
    private fun fixture(block: (ContextWrapper, LocalStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "usability-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        try { LocalStore(context).use { block(context, it) } } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
    }
    private fun request(name: String, json: String) = ToolRequest(name, JSONObject(json))
    @Test fun longNotesRequireEveryPageAndAppendPreservesUnseenText() = fixture { context, store -> runBlocking {
        val chat = store.chat(store.newChat())!!
        val tools = AssistantTools(context, store)
        val body = "A".repeat(5000) + "Keep this ending."
        val id = store.addNote("Long note", body)
        tools.execute(chat, request("read_note", """{"id":$id,"cursor":0}"""), "test")
        val replacement = request("edit_note", """{"id":$id,"version":1,"body":"Changed"}""")
        assertTrue(runCatching { tools.execute(chat, replacement, "test") }.isFailure)
        assertEquals(body, store.notes().single().body)
        tools.execute(chat, request("read_note", """{"id":$id,"cursor":2000}"""), "test")
        tools.execute(chat, request("read_note", """{"id":$id,"cursor":4000}"""), "test")
        assertTrue(tools.succeeded(tools.execute(chat, replacement, "test")))
        assertEquals("Changed", store.notes().single().body)
        // A retry returns the original receipt even though the saved version has advanced.
        assertTrue(tools.succeeded(tools.execute(chat, replacement, "test")))
        assertEquals(2L, store.notes().single().version)
        val append = request("edit_note", """{"id":$id,"version":2,"append":" and more"}""")
        tools.execute(chat, append, "another-turn")
        assertEquals("Changed and more", store.notes().single().body)
        assertTrue(runCatching { tools.execute(chat, request("edit_note", """{"id":$id,"version":1,"title":"Stale"}"""), "stale") }.isFailure)
    } }
    @Test fun completedAndUncertainWritesAreNotReplayed() = fixture { context, store -> runBlocking {
        val chat = store.chat(store.newChat())!!
        val tools = AssistantTools(context, store)
        val create = request("create_note", """{"title":"Groceries","body":"Milk"}""")
        val first = tools.execute(chat, create, "turn:1")
        assertEquals(first, tools.execute(chat, create, "turn:1"))
        assertEquals(1, store.notes().size)
        assertNull(store.beginTool("turn:2", tools.callKey(create)))
        val uncertain = JSONObject(tools.execute(chat, create, "turn:2"))
        assertEquals("uncertain", uncertain.getString("status"))
        assertEquals(1, store.notes().size)
    } }
    @Test fun approvalsAndGoalScopeSurviveReopeningAndPausing() = fixture { context, store ->
        val chat = store.newChat()
        val goal = store.startAgentTask(chat, "Plan a trip")
        val id = store.addMessage(chat, "user", "Save packing list")
        store.saveTurn(id, chat, "Save packing list", "model", "QUICK")
        val pending = PendingAction(request("create_note", """{"title":"Packing","body":"Coat"}"""), "Save packing list", 1, listOf("read_note:1"), listOf("read result"))
        store.updateTurn(id, "approval", pending = pending)
        store.updateAgentTask(goal, "paused", "Stopped")
        LocalStore(context).use { reopened ->
            assertEquals("goal:$goal", reopened.executionScope(chat, id))
            assertEquals("Packing", reopened.turns(chat).single().pending!!.request.arguments.getString("title"))
            reopened.saveTurn(id, chat, "Save corrected packing list", "model2", "DEEP")
            reopened.recoverInterruptedTurns()
            assertEquals("interrupted", reopened.turns(chat).single().status)
            assertEquals("goal:$goal", reopened.executionScope(chat, id))
            assertEquals("Save corrected packing list", reopened.messages(chat).single().body)
        }
    }
    @Test fun chatDeletionRemovesDerivedDataAndHonorsMemoryChoice() = fixture { _, store ->
        val chat = store.newChat()
        val message = store.addMessage(chat, "user", "I like tea")
        store.queueMemory(message); store.addMemory("Likes tea", chat, message); store.suggestMemory("Likes coffee", chat, message)
        store.addAttachment(chat, "private.txt", "text/plain", listOf("private words"))
        store.saveChatSummary(chat, "Private summary", message)
        val goal = store.startAgentTask(chat, "Goal"); store.addTaskEvent(goal, "Private action")
        store.saveTurn(message, chat, "I like tea", "model", "QUICK")
        store.deleteChat(chat, false)
        assertNull(store.chat(chat)); assertTrue(store.attachments(chat).isEmpty()); assertTrue(store.messages(chat).isEmpty())
        assertNull(store.chatSummary(chat)); assertTrue(store.taskEvents(goal).isEmpty()); assertTrue(store.memorySuggestions().isEmpty())
        assertTrue(store.pendingMemory().isEmpty()); assertTrue(store.turns(chat).isEmpty()); assertNull(store.memories().single().sourceChatId)
        val other = store.newChat(); store.addMemory("Likes walks", other)
        store.deleteChat(other, true)
        assertEquals(listOf("Likes tea"), store.memories().map { it.fact })
    }
    @Test fun manualScheduleCompletionPreservesItsNextRegularRun() = fixture { _, store ->
        val time = System.currentTimeMillis() + 86400000
        val id = store.addScheduledTask("Daily", "Say hello", "model", time, "daily")
        store.finishScheduledTask(id, time, "Hello", null, null, manual = true, executionTime = time - 1000)
        val task = store.scheduledTask(id)!!
        assertEquals(time, task.nextRunMillis); assertTrue(task.enabled); assertEquals(time - 1000, task.lastRunMillis)
    }
    @Test fun wrongTypesAndUnknownFieldsFailBeforeWriting() = fixture { context, store ->
        val tools = AssistantTools(context, store)
        listOf(request("create_note", """{"title":"A","body":4}"""), request("create_note", """{"title":"A","body":"B","extra":true}"""), request("read_note", """{"id":1.5}"""))
            .forEach { assertTrue(runCatching { tools.normalize(it) }.isFailure) }
        assertTrue(store.notes().isEmpty())
        assertEquals(ToolCatalog.definitions.size, JSONObject(ToolCatalog.schema()).getJSONArray("oneOf").length())
    }
    @Test fun turningWebOffTakesEffectDuringAnExistingTurn() = fixture { context, store -> runBlocking {
        val chat = store.chat(store.newChat(web = true))!!
        store.setChatFlag(chat.id, "web", false)
        val result = runCatching { AssistantTools(context, store).execute(chat, request("web_search", """{"query":"unused test query"}""")) }
        assertEquals("Web access is off for this chat.", result.exceptionOrNull()?.message)
    } }
}
