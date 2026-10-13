package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MemoryProcessorTest {
    private class Engine(var answer: String) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.ModelReady)
        override val lastPromptProcessingMillis = 0L
        override suspend fun loadModel(pathToModel: String) {}
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override suspend fun resetSystemPrompt(systemPrompt: String) {}
        override suspend fun countTokens(text: String) = text.length / 4
        override suspend fun contextCapacity() = 8192
        override suspend fun setThinking(enabled: Boolean) = true
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = if(answer == "WAIT") kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() } else flowOf(answer)
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int) = ""
        override fun cleanUp() {}
        override fun destroy() {}
    }
    @Test fun allFactsNeedReviewAndFailuresRetry() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "memory-processor-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        LocalStore(context).use { store ->
            val chat = store.newChat()
            val ordinary = store.addMessage(chat, "user", "I prefer tea")
            store.queueMemory(ordinary)
            val engine = Engine("""{"facts":[{"fact":"User prefers tea","kind":"durable","sensitive":false,"expires":null,"evidence":"I prefer tea"}]}""")
            val processor = MemoryProcessor(store, engine)
            val saved = processor.process()
            assertTrue(saved.isEmpty())
            assertTrue(store.memories().isEmpty())
            val tea = store.memorySuggestions().single()
            store.acceptMemorySuggestion(tea.id)
            val savedTeaId = store.memories().single().id
            assertTrue(store.pendingMemory().isEmpty())
            val correction = store.addMessage(chat, "user", "I now prefer coffee instead of tea")
            store.queueMemory(correction)
            engine.answer = """{"facts":[{"fact":"User prefers coffee","kind":"durable","sensitive":false,"expires":null,"evidence":"I now prefer coffee","replaces_id":${savedTeaId}}]}"""
            assertTrue(processor.process().isEmpty())
            assertTrue(store.memories().any { it.fact == "User prefers tea" })
            val suggestion = store.memorySuggestions().first { it.fact == "User prefers coffee" }
            store.acceptMemorySuggestion(suggestion.id)
            assertFalse(store.memories().any { it.fact == "User prefers tea" })
            assertTrue(store.memories().any { it.fact == "User prefers coffee" })
            val interrupted = store.addMessage(chat, "user", "I enjoy walking")
            store.queueMemory(interrupted)
            engine.answer = "WAIT"
            val job = launch { processor.process() }
            kotlinx.coroutines.delay(50)
            job.cancel()
            job.join()
            assertTrue(store.pendingMemory().any { it.id == interrupted })
            assertEquals(0, store.stalledMemoryCount())
            store.completeMemory(interrupted)
            val sensitive = store.addMessage(chat, "user", "I was diagnosed with diabetes")
            store.queueMemory(sensitive)
            engine.answer = """{"facts":[{"fact":"User has diabetes","kind":"durable","sensitive":false,"expires":null,"evidence":"diagnosed with diabetes"}]}"""
            assertTrue(processor.process().isEmpty())
            assertTrue(store.memorySuggestions().any { it.fact == "User has diabetes" })
            assertFalse(store.memories().any { it.fact == "User has diabetes" })
            val failure = store.addMessage(chat, "user", "I prefer coffee")
            store.queueMemory(failure)
            engine.answer = "invalid JSON"
            repeat(3) { processor.process() }
            assertEquals(1, store.stalledMemoryCount())
            store.retryMemory()
            engine.answer = """{"facts":[]}"""
            processor.process()
            assertEquals(0, store.stalledMemoryCount())
            assertTrue(store.pendingMemory().isEmpty())
            store.memories().first { it.fact == "User prefers coffee" }.let { store.deleteMemory(it.id) }
            assertFalse(store.memories().any { it.fact == "User prefers coffee" })
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        Unit
    }
}
