package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in observation harness. Live outcomes require evidence review, not just a green test result. */
class NativeWebGatheringTest {
    private data class Case(val id: String, val request: String, val prior: String? = null)

    @Test fun recordLiveLookups() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live-web opt-in required", args.getString("liveWeb") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = args.getString("modelFile") ?: "Qwen3-0.6B-Q8_0.gguf"
        require(filename.matches(Regex("[A-Za-z0-9_.-]+\\.gguf")))
        val file = File(base.getExternalFilesDir(null), filename)
        assertTrue("Supply the complete requested model: $filename", file.isFile)
        val repeats = (args.getString("repeats")?.toInt() ?: 2).also { require(it in 1..3) }
        val label = (args.getString("runLabel") ?: "live-web").also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) }
        val cases = listOf(
            Case("museum_search", "Search the web for the Canadian Museum of History's free admission day and hours. Give me the day and times with a source."),
            Case("museum_page", "Read https://www.historymuseum.ca/plan-your-visit and tell me when admission is free. Give me the actual day and hours."),
            Case("schedule_followup", "can you please check the schedule for me?",
                args.getString("addressPrior") ?: "I need the garbage schedule for 100 Example Avenue in Ottawa."),
            Case("missing_address", "What is my garbage collection day?")
        ).filter { args.getString("caseIds")?.split(',')?.contains(it.id) != false }
        require(cases.isNotEmpty())
        val evidence = File(base.getExternalFilesDir(null), "$label-results.jsonl")
        val progress = File(base.getExternalFilesDir(null), "$label-progress.txt")
        evidence.writeText("")
        progress.writeText("Real model and live production web tools; no substituted results. Model=$filename\n")
        val db = File(base.noBackupFilesDir, "native-web-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = db }
        ModelAccess.mutex.withLock {
            try { LocalStore(context).use { store ->
                repeat(repeats) { iteration ->
                    for (case in cases) {
                        val record = JSONObject().put("model", filename).put("case", case.id)
                            .put("repeat", iteration + 1).put("request", case.request).put("prior", case.prior)
                        val steps = JSONArray()
                        record.put("steps", steps)
                        val started = android.os.SystemClock.elapsedRealtime()
                        progress.appendText("START ${case.id} repeat=${iteration + 1}\n")
                        val tools = AssistantTools(context, store) // Production network; no injected callbacks or keys.
                        val runtime = AssistantRuntime(context, store) { web, request, recent, files -> tools.menu(web, request, recent, files) }
                        val model = LocalModel(0, filename, file.absolutePath, "test", file.length())
                        val chat = store.chat(store.newChat(web = true))!!
                        case.prior?.let {
                            store.addMessage(chat.id, "user", it)
                            store.addMessage(chat.id, "assistant", "The schedule is available through the city website. Please check it there.")
                        }
                        store.addMessage(chat.id, "user", case.request)
                        try {
                            withTimeout(240_000) {
                                runtime.startMeasurement(model, ResponseMode.QUICK)
                                runtime.use(model, chat, false, YouProfile(), case.request)
                                var raw = runtime.generateTurn(case.request) {}
                                val calls = mutableListOf<String>()
                                val results = mutableListOf<String>()
                                while (true) {
                                    progress.appendText("DECISION ${case.id}: $raw\n")
                                    val request = runtime.parseAction(raw, case.request, results, tools)
                                    if (request == null) {
                                        record.put("status", if (hasFinalAnswer(raw)) "answered" else "empty_answer").put("answer", raw)
                                        break
                                    }
                                    if (request.name !in setOf("web_search", "web_read", "local_find", "find_skill")) {
                                        record.put("status", "unexpected_tool").put("rejected_call", raw)
                                        break // Never execute unrelated actions in a lookup test.
                                    }
                                    val key = tools.callKey(request)
                                    val blocked = ToolTurnPolicy.blockReason(key, false, calls, 3)
                                    if (blocked != null) {
                                        record.put("guard", blocked)
                                        if (blocked == ToolTurnPolicy.REPEATED_CALL && results.isNotEmpty()) {
                                            raw = runtime.recoverTextAnswer(case.request, results)
                                            record.put("status", "recovered_answer").put("answer", raw)
                                        } else record.put("status", "budget_exhausted")
                                        break
                                    }
                                    calls.add(key)
                                    val step = JSONObject().put("name", request.name).put("arguments", request.arguments)
                                    steps.put(step)
                                    val toolStarted = android.os.SystemClock.elapsedRealtime()
                                    val result = try { tools.execute(chat, request) } catch (error: Exception) { tools.failure(error) }
                                    step.put("result", JSONObject(result)).put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - toolStarted)
                                    progress.appendText("RESULT ${case.id} ${request.name}: $result\n")
                                    if (!tools.succeeded(result)) {
                                        record.put("status", "tool_error").put("error", tools.resultMessage(result))
                                        break // Match the production foreground failure path.
                                    }
                                    results.add(result)
                                    raw = runtime.generateToolFollowUp(case.request, request.name, result) {}
                                }
                            }
                        } catch (error: Exception) {
                            record.put("status", "runtime_error").put("error", "${error.javaClass.simpleName}: ${error.message}")
                        } finally {
                            runtime.release()
                            record.put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - started)
                            evidence.appendText(record.toString() + "\n")
                            progress.appendText("END ${case.id} ${record.optString("status")} ${record.optLong("elapsed_ms")}ms\n")
                        }
                    }
                }
            } } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(db) }
        }
        // A completed harness is not a pass score for the model. Review every saved answer and source.
        assertTrue(evidence.length() > 0)
    }
}
