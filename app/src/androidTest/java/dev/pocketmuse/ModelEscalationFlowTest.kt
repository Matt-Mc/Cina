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

    @Test fun simpleAndComplexRequestsKeepTheSelectedModel() = fixture {
        replies(small, TurnDecision.REPLY_CALL, "Hello!")
        send("How are you?")
        assertEquals(small.path,turn().modelPath)
        main { vm.newChat();store.renameChat(vm.active.value,"Single model test") }
        replies(small,TurnDecision.REPLY_CALL,"Here is a detailed comparison.")
        send("Compare the tradeoffs of these two plans in detail")
        assertEquals(small.path,turn().modelPath)
        assertTrue(engine.loads.all { it==small.path })
    }
    @Test fun modelFailureRequiresAnExplicitNewSelection() = fixture {
        replies(small,java.io.IOException("Fixture model failure"))
        send("What is two plus two?")
        assertEquals("failed",turn().status)
        assertEquals(listOf(small.path),engine.loads)
        replies(medium,TurnDecision.REPLY_CALL,"Four.")
        main { vm.setModel(medium.path);vm.retryTurn(turn().id) };waitForIdle()
        assertEquals("completed",turn().status)
        assertEquals(medium.path,turn().modelPath)
        assertEquals(1,store.messages(vm.active.value).count { it.role=="user" })
    }
}
