package dev.pocketmuse

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production ViewModel, runtime, storage and tools, with deterministic model/network fixtures. */
class ModelEscalationFlowTest {
    private class TestApplication(base: Context, val root: File, val prefix: String) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String) = File(root, name)
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
    }

    private class ScriptedEngine : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
        override val lastPromptProcessingMillis = 0L
        val scripts = mutableMapOf<String, ArrayDeque<Any>>()
        val loads = mutableListOf<String>()
        val prompts = mutableListOf<Pair<String, String>>()
        var path = ""
        override suspend fun loadModel(pathToModel: String) { path = pathToModel; loads.add(path); state.value = InferenceEngine.State.ModelReady }
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override suspend fun resetSystemPrompt(systemPrompt: String) {}
        override suspend fun countTokens(text: String) = (text.length + 3) / 4
        override suspend fun contextCapacity() = 16_384
        override suspend fun setThinking(enabled: Boolean) = true
        override suspend fun setToolSchema(schema: String, force: Boolean) {}
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            prompts.add(path to message)
            when (val answer = scripts[path]?.removeFirstOrNull() ?: error("No scripted reply for $path")) {
                is Throwable -> throw answer
                "WAIT" -> awaitCancellation()
                else -> emit(answer.toString())
            }
        }
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int) = ""
        override fun cleanUp() { state.value = InferenceEngine.State.Initialized }
        override fun destroy() = cleanUp()
    }

    private class Fixture(val application: TestApplication, val engine: ScriptedEngine, val store: LocalStore,
        val vm: AssistantViewModel, val holder: ViewModelStore, val small: LocalModel, val medium: LocalModel, val large: LocalModel) {
        fun replies(model: LocalModel, vararg values: Any) { engine.scripts[model.path] = ArrayDeque(values.toList()) }
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        suspend fun send(request: String) { main { assertTrue(vm.send(request)) }; waitForIdle() }
        suspend fun waitForIdle() { withTimeout(15_000) { while (vm.busy.value) delay(10) } }
        fun turn() = store.turns(vm.active.value).single()
    }

    private fun fixture(webSearch: (String) -> String = { "Example city - https://example.com/collection - Tuesday collection" },
        test: suspend Fixture.() -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "model-escalation-${System.nanoTime()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val application = TestApplication(base, root, prefix)
        application.getSharedPreferences("settings", 0).edit().putBoolean("memory", false).putBoolean("default_web", true).commit()
        val store = LocalStore(application)
        val engine = ScriptedEngine()
        val models = listOf("Model-0.5B" to 100_000_000L, "Model-1B" to 200_000_000L, "Model-2B" to 300_000_000L).map { (name, bytes) ->
            val file = File(root, "$name.gguf").apply { writeText("scripted model fixture") }
            store.addModel(name, file.path, "test", bytes)
            store.models().first { it.path == file.path }
        }
        val holder = ViewModelStore()
        lateinit var vm: AssistantViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = AssistantViewModel(application, SavedStateHandle())
            holder.put("test", vm)
            val tools = AssistantTools(application, store, webSearch = webSearch)
            val runtime = AssistantRuntime(application, store, engine) { web, request, recent, files -> tools.menu(web, request, recent, files) }
            AssistantViewModel::class.java.getDeclaredField("runtime").apply { isAccessible = true }.set(vm, runtime)
            AssistantViewModel::class.java.getDeclaredField("tools").apply { isAccessible = true }.set(vm, tools)
            store.renameChat(vm.active.value, "Escalation test")
            vm.refresh()
            vm.setModel(models[0].path)
        }
        val fixture = Fixture(application, engine, store, vm, holder, models[0], models[1], models[2])
        try { fixture.test() }
        finally {
            fixture.main { holder.clear() }
            withTimeout(5000) { ModelAccess.mutex.lock(); ModelAccess.mutex.unlock() }
            store.close()
            root.listFiles()?.filter { it.extension == "db" }?.forEach(SQLiteDatabase::deleteDatabase)
            root.deleteRecursively()
            listOf("settings", "home", "downloads", "skills", "local_performance").forEach { application.getSharedPreferences(it, 0).edit().clear().commit() }
            ModelAccess.owner = null
        }
    }

    @Test fun simpleQaStartsSmallAndComplexWorkStartsLarge() = fixture {
        replies(small, TurnDecision.REPLY_CALL, "Hello!")
        send("How are you?")
        assertEquals(small.path, turn().modelPath)
        main { vm.newChat(); store.renameChat(vm.active.value, "Escalation test") }
        replies(large, TurnDecision.REPLY_CALL, "Here is a detailed comparison.")
        send("Compare the tradeoffs of these two plans in detail")
        assertEquals(large.path, turn().modelPath)
        assertEquals(listOf(small.path, large.path), engine.loads)
    }

    @Test fun invalidActionEscalatesWithoutAddingAnotherUserMessage() = fixture {
        replies(small, "[tool]{\"name\":\"unknown_tool\",\"arguments\":{}}[/tool]")
        replies(medium, TurnDecision.REPLY_CALL, "The answer is four.")
        send("What is two plus two?")
        assertEquals(listOf(small.path, medium.path), engine.loads)
        assertEquals("completed", turn().status)
        assertEquals(2, turn().attempts)
        assertEquals(medium.path, turn().modelPath)
        assertEquals(1, store.messages(vm.active.value).count { it.role == "user" })
        assertTrue(store.actionLog(vm.active.value).any { it.contains("Trying larger model") })
    }

    @Test fun approvedWriteIsNotRepeatedWhenItsModelFailsAfterExecution() = fixture {
        val call = """[tool]{"name":"create_note","arguments":{"title":"Packing","body":"Jacket"}}[/tool]"""
        replies(small, call, java.io.IOException("Fixture model generation failed"))
        replies(medium, "Saved the packing note with Jacket.")
        send("Can you save a note titled Packing with body Jacket and explain what you saved?")
        assertEquals("approval", turn().status)
        assertTrue(store.notes().isEmpty())
        assertEquals(listOf(small.path), engine.loads)
        main { vm.approve() }; waitForIdle()
        assertEquals("completed", turn().status)
        assertEquals(2, turn().attempts)
        assertEquals(1, store.notes().size)
        assertEquals("Jacket", store.notes().single().body)
        assertTrue(engine.prompts.first { it.first == medium.path }.second.contains("Tool result for create_note"))
        assertTrue(engine.prompts.first { it.first == medium.path }.second.contains("Saved note"))
        assertEquals(1, store.messages(vm.active.value).count { it.role == "user" })
    }

    @Test fun failedSearchConnectionDoesNotEscalate() {
        var searches = 0
        fixture(webSearch = { searches++; throw java.io.IOException("Fixture connection failed") }) {
            replies(small, """[tool]{"name":"web_search","arguments":{"query":"Ottawa collection dates"}}[/tool]""")
            send("Search the web for Ottawa collection dates")
            assertEquals("failed", turn().status)
            assertEquals(1, searches)
            assertEquals(listOf(small.path), engine.loads)
        }
    }

    @Test fun successfulSearchIsNotRepeatedWhenTheReplyNeedsALargerModel() {
        var searches = 0
        fixture(webSearch = { searches++; "City collection lookup - https://example.com/collection - Tuesday collection" }) {
            replies(small, """[tool]{"name":"web_search","arguments":{"query":"Ottawa collection dates"}}[/tool]""", java.io.IOException("Fixture generation failed"))
            replies(medium, "The city result lists Tuesday collection: https://example.com/collection")
            send("Search the web for Ottawa collection dates")
            assertEquals("completed", turn().status)
            assertEquals(medium.path, turn().modelPath)
            assertEquals(1, searches)
            assertTrue(engine.prompts.first { it.first == medium.path }.second.contains("Tuesday collection"))
            assertTrue(store.messages(vm.active.value).last().body.contains("Tuesday"))
        }
    }

    @Test fun largerModelCannotRepeatAnAlreadyApprovedWriteOrAskForItsApprovalAgain() = fixture {
        val call = """[tool]{"name":"create_note","arguments":{"title":"Packing","body":"Jacket"}}[/tool]"""
        replies(small, call, java.io.IOException("Fixture generation failed"))
        replies(medium, call, "The packing note was saved with Jacket.")
        send("Can you save a note titled Packing with body Jacket and explain what you saved?")
        main { vm.approve() }; waitForIdle()
        assertEquals("completed", turn().status)
        assertNull(turn().pending)
        assertEquals(1, store.notes().size)
        assertEquals(listOf(small.path, medium.path), engine.loads)
    }

    @Test fun missingDetailsAndRejectedApprovalDoNotEscalate() = fixture {
        replies(small, """[tool]{"name":"create_reminder","arguments":{"title":"Jacket","when_text":"tomorrow at 9 AM"}}[/tool]""")
        send("Remind me to bring a jacket")
        assertEquals("completed", turn().status)
        assertEquals(1, turn().attempts)
        assertTrue(store.reminders().isEmpty())
        assertTrue(store.messages(vm.active.value).last().body.contains("date and time"))
        main { vm.newChat(); store.renameChat(vm.active.value, "Escalation test") }
        replies(small, """[tool]{"name":"create_note","arguments":{"title":"Packing","body":"Jacket"}}[/tool]""")
        send("Save a note titled Packing with body Jacket")
        assertEquals("approval", turn().status)
        main { vm.reject() }
        assertEquals("cancelled", turn().status)
        assertTrue(store.notes().isEmpty())
        assertFalse(engine.loads.contains(medium.path))
    }

    @Test fun exhaustionIsBoundedAndStopDoesNotStartTheNextModel() = fixture {
        listOf(small, medium, large).forEach { replies(it, "invalid decision") }
        send("What is two plus two?")
        assertEquals("failed", turn().status)
        assertEquals(3, turn().attempts)
        assertEquals(listOf(small.path, medium.path, large.path), engine.loads)
        main { vm.newChat(); store.renameChat(vm.active.value, "Escalation test") }
        replies(small, "WAIT")
        main { assertTrue(vm.send("Hello")) }
        withTimeout(5000) { while (engine.prompts.lastOrNull()?.first != small.path) delay(10) }
        val loadsBeforeStop = engine.loads.toList()
        main { vm.stop() }
        delay(100)
        assertEquals(loadsBeforeStop, engine.loads)
        assertEquals("interrupted", turn().status)
    }
}
