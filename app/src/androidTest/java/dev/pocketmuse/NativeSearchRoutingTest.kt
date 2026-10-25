package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real inference; controlled results so this test never invents a real resident's pickup day. */
class NativeSearchRoutingTest {
    @Test fun collectionLookupRunsSearchAndReturnsToTheUser() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val models = listOf("Qwen3-0.6B-Q8_0.gguf", "Qwen3-1.7B-Q8_0.gguf")
            .map { File(base.getExternalFilesDir(null), it) }.filter { it.exists() }
        assumeTrue("Supply a Qwen3 GGUF for the native routing test", models.isNotEmpty())
        val db = File(base.cacheDir, "native-search-routing-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = db }
        val evidence = File(base.getExternalFilesDir(null), "native-search-routing-results.txt")
        evidence.writeText("Real local inference; web responses are test fixtures. Addresses and pickup days below are fictional.\n")
        ModelAccess.mutex.withLock {
            try { LocalStore(context).use { store ->
                for (file in models) withTimeout(360_000) {
                    val queries = mutableListOf<String>()
                    val tools = AssistantTools(context, store, webSearch = { query ->
                        queries.add(query)
                        "Example City collection lookup - https://example.com/collection - 100 Example Avenue, Ottawa: garbage is collected every Tuesday."
                    })
                    val runtime = AssistantRuntime(context, store) { web, request, recent, files -> tools.menu(web, request, recent, files) }
                    val model = LocalModel(0, file.name, file.absolutePath, "test", file.length())
                    val chat = store.chat(store.newChat(web = true))!!
                    val original = "good! I'm trying to figure out the garbage schedule for my new house in Ottawa. I live at 100 Example Avenue"
                    try {
                        runtime.startMeasurement(model, ResponseMode.QUICK)
                        runtime.use(model, chat, false, YouProfile(), original)
                        var raw = runtime.generateTurn(original) {}
                        evidence.appendText("\nModel: ${file.name}\nFirst decision: $raw\n")
                        val calls = mutableListOf<String>()
                        val results = mutableListOf<String>()
                        var finished = false
                        for (step in 0 until 5) {
                            val request = runtime.parseAction(raw, original, results, tools)
                            if (request == null) { finished = true; break }
                            assertFalse("Lookup must not request a write", tools.needsConfirmation(request))
                            val key = tools.callKey(request)
                            val blocked = ToolTurnPolicy.blockReason(key, false, calls, 3)
                            if (blocked == ToolTurnPolicy.REPEATED_CALL && results.isNotEmpty()) {
                                raw = runtime.recoverTextAnswer(original, results)
                                evidence.appendText("Repeated call blocked; final text from confirmed results: $raw\n")
                                finished = true
                                break
                            }
                            assertNull(blocked)
                            calls.add(key)
                            val result = tools.execute(chat, request)
                            assertTrue(result, tools.succeeded(result))
                            results.add(result)
                            raw = runtime.generateToolFollowUp(original, request.name, result) {}
                            evidence.appendText("Executed: ${request.name}\nContinuation: $raw\n")
                        }
                        assertTrue("No actual search: $raw", queries.isNotEmpty())
                        assertTrue(queries.toString(), queries.any { it.contains("Ottawa", true) && it.contains("Example", true) })
                        assertTrue("Turn never finished: $raw", finished && hasFinalAnswer(raw))
                        assertFalse(TurnDecision.promisesWork(raw, original))
                        assertTrue("Final answer ignored the search result: $raw", raw.contains("Tuesday", true))
                        val citedUrls = Regex("https?://[^\\s)]+" ).findAll(raw).map { it.value.trimEnd('.', ',') }.toList()
                        assertTrue("Invented source: $raw", citedUrls.all { it == "https://example.com/collection" })
                        evidence.appendText("PASS: search executed and final answer returned\n")
                    } finally { runtime.release() }
                }
            } } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(db) }
        }
    }
}
