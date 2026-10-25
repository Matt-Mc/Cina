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

    @Test fun autoStartsSmallForQaAndLargeForComplexWorkWithoutAssignments() {
        assertEquals(listOf(small, medium, large), plan(ResponseMode.QUICK))
        assertEquals(listOf(large), plan(ResponseMode.DEEP))
    }

    @Test fun configuredSlotsTakePrecedenceAndRetryStartsAtTheLastModel() {
        assertEquals(medium, plan(ResponseMode.QUICK, quick = medium.path).first())
        assertEquals(medium, plan(ResponseMode.DEEP, heavy = medium.path).first())
        assertEquals(listOf(medium, large), ModelRouting.plan(models, ResponseMode.AUTO, ResponseMode.QUICK,
            small.path, small.path, large.path, retryFrom = medium.path))
        assertEquals(large, ModelRouting.plan(models, ResponseMode.QUICK, ResponseMode.QUICK, large.path, null, null).first())
    }

    @Test fun parameterCountsBeatQuantizationFileSizesAndEqualSizeVariantsAreNotEscalations() {
        val three = model("Model-3B-Q8", 3_600_000_000)
        val four = model("Model-4B-Q4", 2_400_000_000)
        val variant = four.copy(path = "/variant.gguf", bytes = 4_400_000_000)
        assertEquals(listOf(three, four), ModelRouting.plan(listOf(variant, four, three), ResponseMode.AUTO, ResponseMode.QUICK, null, null, null))
        assertEquals(0.135, ModelRouting.size(model("Smol-135M", 100_000_000)), 0.0001)
    }

    @Test fun unsuitableFallbacksDuplicatesAndMissingAssignmentsAreExcluded() {
        assertEquals(listOf(small, medium), ModelRouting.plan(models + small, ResponseMode.AUTO, ResponseMode.QUICK,
            null, "/missing", null, suitable = { it.path != large.path }))
        assertTrue(ModelRouting.plan(emptyList(), ResponseMode.AUTO, ResponseMode.QUICK, null, null, null).isEmpty())
        val extra = model("Model-8B", 4_000_000_000)
        assertEquals(ModelRouting.MAX_ATTEMPTS, ModelRouting.plan(models + extra, ResponseMode.AUTO, ResponseMode.QUICK, null, null, null).size)
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
