package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional integration test: supply a real GGUF in the test app's external files directory. */
class NativeToolGrammarTest {
    @Test fun forcedRepairSamplerConstrainsActionJsonAndResetsForOrdinaryReplies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.getExternalFilesDir(null), InstrumentationRegistry.getArguments().getString("nativeModel") ?: "audit-model.gguf")
        assumeTrue("Supply audit-model.gguf to run native generation", model.exists())
        ModelAccess.mutex.withLock {
            val engine = AiChat.getInferenceEngine(context)
            withTimeout(30_000) { engine.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady } }
            if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
            try {
                engine.loadModel(model.absolutePath)
                engine.setThinking(false)
                engine.resetSystemPrompt("Reply with exactly one action and nothing else. The format is [tool]{\"name\":\"create_note\",\"arguments\":{\"title\":\"Groceries\",\"body\":\"Milk\"}}[/tool]")
                val allSchemas = JSONObject(ToolCatalog.schema()).getJSONArray("oneOf")
                val noteSchema = (0 until allSchemas.length()).map { allSchemas.getJSONObject(it) }
                    .first { it.getJSONObject("properties").getJSONObject("name").getString("const") == "create_note" }
                engine.setToolSchema(noteSchema.toString(), force = true)
                val answer = StringBuilder()
                withTimeout(60_000) { engine.sendUserPrompt("Save a note titled Groceries with body Milk. Use the action format.", 192).collect { answer.append(it) } }
                val visible = splitModelResponse(answer.toString()).answer
                assertTrue("Expected action, got: $visible", visible.startsWith("[tool]"))
                assertTrue("Expected complete action: $visible", visible.trim().endsWith("[/tool]"))
                val action = JSONObject(visible.removePrefix("[tool]").trim().removeSuffix("[/tool]").trim())
                assertEquals("create_note", action.getString("name"))
                ToolCatalog.get(action.getString("name"))!!.validate(action.getJSONObject("arguments"))
                engine.resetSystemPrompt("Reply to greetings with a short greeting. Do not call tools.")
                val greeting = StringBuilder()
                withTimeout(30_000) { engine.sendUserPrompt("Hello", 32).collect { greeting.append(it) } }
                assertTrue(greeting.isNotBlank())
                assertFalse(greeting.contains("[tool]"))
            } finally { engine.cleanUp(); ModelAccess.owner = null }
        }
    }
}
