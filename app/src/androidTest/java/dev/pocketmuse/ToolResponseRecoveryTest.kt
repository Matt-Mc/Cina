package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Deterministic runtime tests: model responses are scripted; parsing, storage and tools are real. */
class ToolResponseRecoveryTest {
    private class ScriptedEngine(vararg replies: String) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
        override val lastPromptProcessingMillis = 0L
        val remaining = ArrayDeque(replies.toList())
        var systemPrompt = ""
        var thinking = false
        var forced = false
        var forcedCalls = 0
        override suspend fun loadModel(pathToModel: String) { state.value = InferenceEngine.State.ModelReady }
        override suspend fun setSystemPrompt(systemPrompt: String) { this.systemPrompt = systemPrompt }
        override suspend fun resetSystemPrompt(systemPrompt: String) { this.systemPrompt = systemPrompt; forced = false }
        override suspend fun countTokens(text: String) = (text.length + 3) / 4
        override suspend fun contextCapacity() = 8192
        override suspend fun setThinking(enabled: Boolean): Boolean { thinking = enabled; return true }
        override suspend fun setToolSchema(schema: String, force: Boolean) { forced = force; if (force) forcedCalls++ }
        override fun sendUserPrompt(message: String, predictLength: Int) = flowOf(remaining.removeFirst())
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int) = ""
        override fun cleanUp() { state.value = InferenceEngine.State.Initialized }
        override fun destroy() = cleanUp()
    }

    private fun fixture(block: suspend (ContextWrapper, LocalStore) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "tool-response-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        ModelAccess.mutex.withLock {
            try { LocalStore(context).use { block(context, it) } }
            finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file); ModelAccess.owner = null }
        }
    }

    @Test fun repairedCallRestoresAssistantThenUsesTheExecutionResultForAnAnswer() = fixture { context, store ->
        val repaired = """[tool]{"name":"create_note","arguments":{"title":"Packing","body":"Bring a jacket"}}[/tool]"""
        val engine = ScriptedEngine(repaired, "Saved your packing note: bring a jacket.")
        val tools = AssistantTools(context, store)
        val runtime = AssistantRuntime(context, store, engine) { web, request, recent, files -> tools.specification(web, request, recent, files) }
        val model = LocalModel(0, "Scripted", "scripted.gguf", "test", 0)
        val chat = store.chat(store.newChat())!!
        val original = "Can you save a packing note to bring a jacket?"
        try {
            runtime.startMeasurement(model, ResponseMode.DEEP)
            runtime.use(model, chat, false, YouProfile(), original)
            val normalPrompt = engine.systemPrompt
            val previous = "{\"status\":\"success\",\"message\":\"Earlier action completed\"}"
            val request = runtime.parseAction("[create_note]title: Packing, body: Bring a jacket[/create_note]", original, listOf(previous), tools)!!
            assertEquals("create_note", request.name)
            assertTrue(engine.systemPrompt.startsWith(normalPrompt))
            assertTrue(engine.systemPrompt.contains(previous))
            assertFalse(engine.systemPrompt.contains("Repair only the malformed action"))
            assertTrue(engine.thinking)
            assertFalse(engine.forced)
            assertEquals(1, engine.forcedCalls)
            assertTrue(tools.needsConfirmation(request))
            assertTrue(store.notes().isEmpty()) // Parsing/repair cannot perform the write or bypass approval.
            val result = tools.execute(chat, request, "test:approved")
            assertTrue(tools.succeeded(result))
            val answer = runtime.generate(AssistantPrompt.toolFollowUp(original, request.name, result)) {}
            assertNull(runtime.parseAction(answer, original, listOf(result), tools))
            assertTrue(hasFinalAnswer(answer))
            assertEquals(1, store.notes().size)
            assertTrue(engine.remaining.isEmpty())
        } finally { runtime.release() }
    }

    @Test fun screenshotSearchIsRepairedButASecondBadCallCannotLoop() = fixture { context, store ->
        val canonical = """[tool]{"name":"web_search","arguments":{"query":"stores around ottawa that sell riftbound"}}[/tool]"""
        val engine = ScriptedEngine(canonical)
        val tools = AssistantTools(context, store)
        val runtime = AssistantRuntime(context, store, engine) { web, request, recent, files -> tools.specification(web, request, recent, files) }
        val original = "Search for Riftbound stores around Ottawa"
        try {
            val chat = store.chat(store.newChat())!!.copy(web = true)
            runtime.use(LocalModel(0, "Scripted", "scripted.gguf", "test", 0), chat, false, YouProfile(), original)
            val malformed = """[web_search]{"name":"search","arguments":{"query":"stores around ottawa that sell riftbound"}}[/web_search]"""
            val request = runtime.parseAction(malformed, original, emptyList(), tools)!!
            assertEquals("web_search", request.name)
            assertEquals("stores around ottawa that sell riftbound", request.arguments.getString("query"))
            assertFalse(tools.needsConfirmation(request))
            assertTrue(runCatching { runtime.parseAction(malformed, original, emptyList(), tools) }.isFailure)
            assertEquals(1, engine.forcedCalls)
        } finally { runtime.release() }
    }

    @Test fun failedRepairAndInvalidValuesCannotBecomeSuccessesOrWriteData() = fixture { context, store ->
        val tools = AssistantTools(context, store)
        val engine = ScriptedEngine("Still not an action")
        val runtime = AssistantRuntime(context, store, engine) { web, request, recent, files -> tools.specification(web, request, recent, files) }
        try {
            runtime.use(LocalModel(0, "Scripted", "scripted.gguf", "test", 0), Chat(0, "Test", false, false), false, YouProfile(), "Save a note")
            assertTrue(runCatching { runtime.parseAction("[create_note]bad[/create_note]", "Save a note", emptyList(), tools) }.isFailure)
            val invalid = tools.parse("""[tool]{"name":"create_note","arguments":{"title":"Packing","body":42}}[/tool]""")!!
            assertTrue(runCatching { tools.normalize(invalid) }.isFailure)
            assertTrue(store.notes().isEmpty())
        } finally { runtime.release() }
    }
}
