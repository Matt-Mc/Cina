package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InboxInterpretationTest {
    private val body = "I prefer tea. Send the draft tomorrow."
    private fun response(kind: String = "fact", text: String = "Prefers tea", quote: String = "I prefer tea.", passage: String = "p0") =
        JSONObject().put("proposals", org.json.JSONArray().put(JSONObject().put("kind", kind).put("text", text).put("passage", passage).put("quote", quote))).toString()
    private fun fixture(block: (LocalStore, InboxRepository, InboxDetail) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "interpretation-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        try { LocalStore(context).use { store ->
            val repo = InboxRepository(store)
            repo.capture("source", CapturedSource("Source", "text/plain", body.toByteArray(), body, "Test"))
            block(store, repo, repo.detail(repo.items().single())!!)
        } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    @Test fun validatesExactEvidenceAndRejectsUnsupportedStructures() = fixture { _, _, source ->
        val passages = InboxInterpretation.passages(source)
        val suggestion = InboxInterpretation.parse(response(), passages).single()
        assertEquals(0, suggestion.start); assertEquals("I prefer tea.".length, suggestion.end)
        listOf(response(quote = "I prefer coffee."), response(passage = "p99"), response(kind = "send_email"),
            """{"proposals":[],"tool":"send_email"}""", "[tool]send_email[/tool]", response(quote = "I"),
            response(text = "x".repeat(601))).forEach { assertTrue(it, runCatching { InboxInterpretation.parse(it, passages) }.isFailure) }
    }
    @Test fun invalidBatchCannotPartiallySaveAndFailureIsVisible() = fixture { _, repo, source ->
        val good = InboxInterpretation.parse(response(), InboxInterpretation.passages(source)).single()
        assertTrue(runCatching { repo.saveInterpretation("source", 1, InterpretationResult(listOf(good, good.copy(quote = "wrong")), "test coverage"), "test model") }.isFailure)
        assertTrue(repo.review("source")!!.proposals.isEmpty())
        repo.interpretationFailed("source", 1, "Evidence did not match")
        assertEquals("Evidence did not match", repo.review("source")!!.error)
        repo.saveInterpretation("source", 1, InterpretationResult(listOf(good), "test coverage"), "test model")
        assertEquals("", repo.review("source")!!.error)
    }
    @Test fun editsDecisionsAndCategoryPersistWithoutCreatingActions() = fixture { store, repo, source ->
        val suggestions = listOf("fact", "filing", "action", "question").map {
            InboxInterpretation.parse(response(it), InboxInterpretation.passages(source)).single()
        }
        repo.saveInterpretation("source", 1, InterpretationResult(suggestions, "Source characters 1–38"), "Fixture model")
        for (proposal in repo.review("source")!!.proposals) {
            val edited = if (proposal.suggestion.kind == "filing") "Personal preferences" else "Edited ${proposal.suggestion.kind}"
            repo.updateProposal("source", proposal.id, 1, edited, "accepted")
        }
        val reopened = InboxRepository(store).review("source")!!
        assertEquals("reviewed", reopened.state)
        assertTrue(reopened.proposals.all { it.edited && it.state == "accepted" })
        assertEquals("Personal preferences", repo.items().single().category)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(store.readableDatabase.path)
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = path }
        LocalStore(context).use { restored ->
            val persisted = InboxRepository(restored).review("source")!!
            assertEquals(reopened, persisted)
            assertEquals("Personal preferences", InboxRepository(restored).items().single().category)
        }
        assertTrue(store.notes().isEmpty()); assertTrue(store.memories().isEmpty()); assertTrue(store.reminders().isEmpty()); assertTrue(store.scheduledTasks().isEmpty())
        val proposal = reopened.proposals.first()
        assertTrue(runCatching { repo.updateProposal("source", proposal.id, 1, "Stale edit", "accepted") }.isFailure)
        repo.updateProposal("source", proposal.id, 2, "Corrected", "pending")
        assertEquals("needs_review", repo.review("source")!!.state)
        assertEquals("I prefer tea.", repo.review("source")!!.proposals.first().suggestion.quote)
    }
    @Test fun repeatProcessingNeverOverwritesDecisionsAndDeletedSourcesCannotReturn() = fixture { _, repo, source ->
        val result = InterpretationResult(InboxInterpretation.parse(response(), InboxInterpretation.passages(source)), "test coverage")
        repo.saveInterpretation("source", 1, result, "test")
        val proposal = repo.review("source")!!.proposals.single()
        repo.updateProposal("source", proposal.id, 1, proposal.suggestion.text, "deferred")
        assertTrue(runCatching { repo.saveInterpretation("source", 1, result, "test") }.isFailure)
        assertEquals("deferred", repo.review("source")!!.proposals.single().state)
        repo.updateProposal("source", proposal.id, 2, proposal.suggestion.text, "discarded")
        assertEquals("reviewed", repo.review("source")!!.state)
        repo.delete("source")
        assertTrue(runCatching { repo.saveInterpretation("source", 1, result, "test") }.isFailure)
        assertTrue(repo.items().isEmpty())
    }
    private class Engine(var output: String) : InferenceEngine {
        override val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.ModelReady)
        override val lastPromptProcessingMillis = 0L
        var prompt = ""
        var policy = ""
        override suspend fun loadModel(pathToModel: String) {}
        override suspend fun setSystemPrompt(systemPrompt: String) {}
        override suspend fun resetSystemPrompt(systemPrompt: String) { policy = systemPrompt }
        override suspend fun countTokens(text: String) = text.length / 4
        override suspend fun contextCapacity() = 8192
        override suspend fun setThinking(enabled: Boolean) = true
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> {
            prompt = message
            return if (output == "WAIT") flow { awaitCancellation() } else flowOf(output)
        }
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int) = ""
        override fun cleanUp() {}
        override fun destroy() {}
    }
    @Test fun generationBoundsAndDisclosesScopeAndCancellationDoesNotSave() = fixture { _, repo, source -> runBlocking {
        val engine = Engine(response())
        val result = InboxInterpretation.generate(engine, source.copy(text = body + "x".repeat(30_000)))
        assertTrue(result.coverage.contains("9600")); assertFalse(engine.prompt.contains("tool_specification"))
        assertTrue(engine.policy.contains("untrusted data")); assertTrue(repo.review("source")!!.proposals.isEmpty())
        engine.output = "WAIT"
        val job = launch { InboxInterpretation.generate(engine, source) }
        yield(); job.cancelAndJoin()
        assertTrue(repo.review("source")!!.proposals.isEmpty())
    } }
    @Test fun captionEvidenceUsesItsOwnOffsets() = fixture { _, _, source ->
        val caption = source.copy(accompanyingText = "Shared by Alex: trip notes")
        val proposal = InboxInterpretation.parse(response(quote = "trip notes", passage = "c0"), InboxInterpretation.passages(caption)).single()
        assertEquals("caption", proposal.field)
        assertEquals("trip notes", caption.accompanyingText.substring(proposal.start, proposal.end))
    }
}
