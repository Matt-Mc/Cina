package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in real Qwen generation through the production runtime, parser, tools and continuation. */
class NativeToolCycleTest {
    @Test fun qwenSearchReminderAndNoteCycles() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(base.getExternalFilesDir(null), "Qwen3-1.7B-Q8_0.gguf")
        assumeTrue("Supply the Qwen3-1.7B Q8_0 GGUF to run this integration test", modelFile.exists())
        val file = File(base.cacheDir, "native-tool-cycle-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        val evidence = File(base.getExternalFilesDir(null), "native-tool-cycle-results.txt")
        evidence.writeText("Model: ${modelFile.name}\nWeb results are controlled fixtures; note/reminder tools use real local storage.\n")
        ModelAccess.mutex.withLock {
            LocalStore(context).use { store ->
                val tools = AssistantTools(context, store, webSearch = {
                    "Fixture Games - https://example.com/ottawa-riftbound - Ottawa shop; Riftbound listed in stock.\n" +
                        "Fixture Cards - https://example.com/cards - Ottawa shop; call to confirm Riftbound stock."
                })
                val runtime = AssistantRuntime(context, store) { web, request, recent, files -> tools.specification(web, request, recent, files) }
                val model = LocalModel(0, modelFile.name, modelFile.absolutePath, "test", modelFile.length())
                // Long JNI prompt processing must allow ART to suspend for garbage collection.
                val collections = AtomicInteger()
                val gc = launch(Dispatchers.Default) {
                    while (isActive) { System.gc(); collections.incrementAndGet(); delay(500) }
                }
                try {
                    val cases = listOf(
                        "can you search for stores around Ottawa that sell riftbound?" to "web_search",
                        "set a reminder to bring a jacket in 10 minutes" to "create_reminder",
                        "Save a note titled Packing with body Bring a jacket" to "create_note",
                        "Find notes about Packing and tell me what they say" to "search_notes"
                    )
                    for (round in 0..2) for ((original, expectedTool) in (if (round == 2) cases.take(2) else cases)) withTimeout(180_000) {
                        val chat = store.chat(store.newChat(web = true))!!
                        if (round == 1) {
                            store.addMessage(chat.id, "user", "Search for stores around Ottawa that sell Riftbound")
                            store.addMessage(chat.id, "assistant", "web_search(\"stores around Ottawa that sell riftbound\")")
                        }
                        store.addMessage(chat.id, "user", original)
                        val reminderCount = store.reminders().size
                        val noteCount = store.notes().size
                        val mode = if (round == 2) ResponseMode.DEEP else ResponseMode.QUICK
                        runtime.startMeasurement(model, mode)
                        runtime.use(model, chat, false, YouProfile(), original)
                        val calls = mutableListOf<String>()
                        val results = mutableListOf<String>()
                        var answer = runtime.generate(original) {}
                        evidence.appendText("\nRound: $round; mode: $mode\nRequest: $original\nInitial: $answer\n")
                        var finished = false
                        for (step in 0 until 6) {
                            val call = runtime.parseAction(answer, original, results, tools)
                            if (call == null) { finished = true; break }
                            val normalized = tools.normalize(call)
                            val key = tools.callKey(normalized)
                            val blocked = ToolTurnPolicy.blockReason(key, tools.needsConfirmation(normalized), calls, 3)
                            if (blocked != null) {
                                answer = runtime.recoverTextAnswer(original, results)
                                evidence.appendText("Guard: $blocked\n")
                                finished = true; break
                            }
                            calls.add(key)
                            // This test explicitly approves its own local fixture writes.
                            val result = tools.execute(chat, normalized, "native:${chat.id}")
                            assertTrue(result, tools.succeeded(result))
                            results.add(result)
                            evidence.appendText("Executed: ${call.name}\nResult: $result\n")
                            answer = runtime.generate(AssistantPrompt.toolFollowUp(original, call.name, result)) {}
                            evidence.appendText("Continuation: $answer\n")
                        }
                        assertTrue("No $expectedTool execution. Model said: $answer", calls.any { it.startsWith("$expectedTool:") })
                        assertTrue("Turn did not finish: $answer", finished && hasFinalAnswer(answer) && answer != EMPTY_REPLY_FALLBACK)
                        if (expectedTool == "web_search") assertTrue("Search answer ignored fixture results: $answer", answer.contains("Fixture", true) || answer.contains("example.com"))
                        if (expectedTool == "create_reminder") assertEquals(reminderCount + 1, store.reminders().size)
                        if (expectedTool == "create_note") { assertEquals(noteCount + 1, store.notes().size); assertTrue(store.notes().all { it.body == "Bring a jacket" }) }
                        evidence.appendText("PASS: $expectedTool\n")
                    }
                    val chat = store.chat(store.newChat())!!
                    val incomplete = "Set a reminder to bring a jacket"
                    runtime.startMeasurement(model, ResponseMode.QUICK)
                    runtime.use(model, chat, false, YouProfile(), incomplete)
                    val answer = runtime.generate(incomplete) {}
                    evidence.appendText("\nMissing time request: $incomplete\nAnswer: $answer\n")
                    val parsed = runCatching { runtime.parseAction(answer, incomplete, emptyList(), tools) }
                    if (parsed.isFailure) assertTrue(parsed.exceptionOrNull().toString(), parsed.exceptionOrNull() is ToolDetailsRequiredException)
                    else assertTrue("Missing time must not become an executable reminder", parsed.getOrNull()?.let { !tools.needsConfirmation(it) || runCatching { tools.normalize(it) }.isFailure } != false)
                    evidence.appendText("PASS: missing-time guard\n")
                    assertTrue(collections.get() > 0)
                    evidence.appendText("PASS: ${collections.get()} garbage collections during native inference\n")
                } finally {
                    gc.cancelAndJoin()
                    store.reminders().forEach { ReminderReceiver.cancel(context, it.id) }
                    runtime.release()
                }
            }
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
        Unit
    }
}
