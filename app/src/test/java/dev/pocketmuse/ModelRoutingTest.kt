package dev.pocketmuse

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModelRoutingTest {
    private fun model(name: String, bytes: Long, id: Long = bytes) = LocalModel(id, name, "/$name.gguf", "test", bytes)
    private val small = model("Qwen-0.6B", 600_000_000)
    private val medium = model("Qwen-1.7B", 1_800_000_000)
    private val large = model("Qwen-4B", 2_500_000_000)
    private val models = listOf(large, small, medium)
    private fun plan(resolved: ResponseMode, quick: String? = null, heavy: String? = null) =
        ModelRouting.plan(models, ResponseMode.AUTO, resolved, large.path, quick, heavy)

    @Test fun everyResponseModeUsesOnlyTheSelectedPhoneModel() {
        ResponseMode.entries.forEach { mode ->
            assertEquals(listOf(small),ModelRouting.plan(models,mode,ResponseMode.DEEP,small.path,medium.path,large.path,large.path))
        }
    }
    @Test fun missingSelectedModelDoesNotSilentlyChooseAnother() {
        assertTrue(ModelRouting.plan(models,ResponseMode.AUTO,ResponseMode.QUICK,"/missing",small.path,large.path).isEmpty())
        assertTrue(ModelRouting.plan(models,ResponseMode.AUTO,ResponseMode.QUICK,null,small.path,large.path).isEmpty())
    }

    @Test fun detectedFailureMovesUpOnceAndStopsOnSuccess() = runBlocking {
        val tried = mutableListOf<String>()
        val answer = ModelEscalation.run(models = listOf(small, medium, large)) { model, _ ->
            tried.add(model.path)
            if (model == small) throw ModelAttemptFailure("Invalid action")
            "Done"
        }
        assertEquals("Done", answer)
        assertEquals(listOf(small.path, medium.path), tried)
    }

    @Test fun attemptsAreBoundedAndEachModelIsUsedOnce() = runBlocking {
        val tried = mutableListOf<String>()
        assertTrue(runCatching {
            ModelEscalation.run(listOf(small, small, medium, large, model("Model-8B", 4_000_000_000))) { model, _ ->
                tried.add(model.path); throw ModelAttemptFailure("Bad output")
            }
        }.exceptionOrNull() is ModelAttemptFailure)
        assertEquals(listOf(small.path, medium.path, large.path), tried)
    }

    @Test fun ordinaryFailuresAndMemoryFailuresDoNotTryLargerModels() = runBlocking {
        for (failure in listOf(java.io.IOException("Connection unavailable"), ModelAttemptFailure("Load failed", IllegalStateException("failed to allocate weights")))) {
            var attempts = 0
            assertSame(failure, runCatching { ModelEscalation.run(models) { _, _ -> attempts++; throw failure } }.exceptionOrNull())
            assertEquals(1, attempts)
        }
    }

    @Test fun inferenceTimeoutEscalatesButParentCancellationDoesNot() = runBlocking {
        var attempts = 0
        assertEquals("Done", ModelEscalation.run(listOf(small, medium)) { _, index ->
            attempts++
            ModelEscalation.step(10, "Generation failed") { if (index == 0) delay(10_000); "Done" }
        })
        assertEquals(2, attempts)
        attempts = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            ModelEscalation.run(listOf(small, medium)) { _, _ ->
                attempts++
                ModelEscalation.step(10_000, "Generation failed") { started.complete(Unit); awaitCancellation() }
            }
        }
        started.await(); job.cancelAndJoin()
        assertEquals(1, attempts)
    }
}
