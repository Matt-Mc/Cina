package dev.pocketmuse

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException

/** Model size is a routing hint, not a guarantee of answer quality. Explicit slot choices take precedence. */
internal object ModelRouting {
    const val MAX_ATTEMPTS = 3
    private val parameters = Regex("""(?:^|[^a-z0-9])(\d+(?:\.\d+)?)\s*([bm])(?=$|[^a-z])""", RegexOption.IGNORE_CASE)

    fun size(model: LocalModel): Double {
        val match = parameters.find(model.name) ?: parameters.find(java.io.File(model.path).name)
        return match?.let { it.groupValues[1].toDouble() / if (it.groupValues[2].equals("m", true)) 1000 else 1 }
            ?: (model.bytes.coerceAtLeast(1).toDouble() / 1_000_000_000)
    }

    fun plan(models: List<LocalModel>, requested: ResponseMode, resolved: ResponseMode,
        selected: String?, quick: String?, heavy: String?, retryFrom: String? = null,
        suitable: (LocalModel) -> Boolean = { true }): List<LocalModel> {
        val available = models.distinctBy { it.path }
        val ranked = available.filter(suitable).sortedWith(compareBy<LocalModel>(::size).thenBy { it.bytes }.thenBy { it.path })
        fun find(path: String?) = available.firstOrNull { it.path == path }
        val initial = find(retryFrom) ?: find(if (resolved == ResponseMode.DEEP) heavy else quick)
            ?: (if (requested == ResponseMode.AUTO) {
                if (resolved == ResponseMode.DEEP) ranked.lastOrNull() else ranked.firstOrNull()
            } else find(selected))
            ?: find(selected) ?: ranked.firstOrNull() ?: return emptyList()
        // Equal parameter counts are quantization variants, not the next larger model.
        val larger = ranked.filter { size(it) > size(initial) }.distinctBy(::size)
        return (listOf(initial) + larger).take(MAX_ATTEMPTS)
    }
}

/** Only failures in model work enter the ladder. Tool/network failures and user decisions do not. */
internal class ModelAttemptFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal object ModelEscalation {
    suspend fun <T> step(timeoutMillis: Long, message: String, block: suspend () -> T): T = try {
        withTimeout(timeoutMillis) { block() }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw ModelAttemptFailure("The model took too long to finish this step.", error)
    } catch (error: CancellationException) { throw error }
    catch (error: ToolDetailsRequiredException) { throw error }
    catch (error: ModelAttemptFailure) { throw error }
    catch (error: Exception) { throw ModelAttemptFailure(message, error) }

    suspend fun <T> run(models: List<LocalModel>, onAttempt: suspend (LocalModel, Int) -> Unit = { _, _ -> },
        attempt: suspend (LocalModel, Int) -> T): T {
        require(models.isNotEmpty()) { "Choose a downloaded model first." }
        var lastFailure: ModelAttemptFailure? = null
        for ((index, model) in models.distinctBy { it.path }.take(ModelRouting.MAX_ATTEMPTS).withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            onAttempt(model, index)
            try { return attempt(model, index) }
            catch (error: ModelAttemptFailure) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // A larger model needs more memory, so an allocation failure is terminal.
                if (Regex("out of memory|not enough memory|insufficient memory|failed to allocate", RegexOption.IGNORE_CASE)
                        .containsMatchIn(error.cause?.message.orEmpty() + " " + error.message.orEmpty())) throw error
                lastFailure = error
            }
        }
        throw lastFailure!!
    }
}

internal data class ToolCheckpoint(val request: ToolRequest, val result: String)
internal class ModelTurnProgress(val turnId: Long, calls: List<String> = emptyList(), results: List<String> = emptyList()) {
    var calls = calls; private set
    var results = results; private set
    val completed = mutableListOf<ToolCheckpoint>()
    init {
        calls.zip(results).forEach { (key, result) ->
            runCatching { ToolCheckpoint(ToolRequest(key.substringBefore(':'), org.json.JSONObject(key.substringAfter(':'))), result) }
                .getOrNull()?.let(completed::add)
        }
    }
    fun record(request: ToolRequest, result: String, executed: List<String>, outcomes: List<String>) {
        calls = executed; results = outcomes; completed.add(ToolCheckpoint(request, result))
    }
    fun context(): String = results.joinToString("\n")
}
