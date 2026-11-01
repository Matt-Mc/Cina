package dev.pocketmuse

import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class MapServiceException(val code: Int) : IOException("Map service returned HTTP $code.")
internal class IncompleteMapException : IOException("The map service returned an incomplete area. Try a smaller radius or retry later.")

/** One bounded transfer; cancellation closes the request even while waiting for headers. */
internal class EssentialsDownload(
    private val endpoint: String = PRIMARY_ENDPOINT,
    private val readTimeoutMs: Int = 80_000,
    private val deadlineMs: Long = 100_000
) {
    suspend fun fetch(point: GeoPoint, radius: Int, progress: suspend (String) -> Unit = {}): ByteArray = coroutineScope {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/json")
            .header("User-Agent", "Cina-Android/1.0 (https://github.com/Matt-Mc/Cina)")
            .post(FormBody.Builder().add("data", Essentials.query(point, radius)).build()).build()
        val client = NetworkClient.client.newBuilder().readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(deadlineMs, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(request)
        val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (response.code != 200) throw MapServiceException(response.code)
                    val body = requireNotNull(response.body) { "Map service returned no records." }
                    require(body.contentLength() <= CollectionDownloadWorker.MAX_BYTES) { "Area download is too large. Choose a smaller radius." }
                    body.byteStream().use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var reported = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= CollectionDownloadWorker.MAX_BYTES) { "Area download exceeds 12 MB. Choose a smaller radius." }
                            output.write(buffer, 0, count)
                            if (output.size() - reported > 256 * 1024) { reported = output.size(); progress("Downloaded ${output.size()/1024} KB…") }
                        }
                        output.toByteArray()
                    }
                }
            }
        } finally { watcher.cancelAndJoin() }
    }

    companion object {
        const val PRIMARY_ENDPOINT = "https://overpass.private.coffee/api/interpreter"
        const val RETRY_ENDPOINT = "https://overpass-api.de/api/interpreter"
        fun retryable(error: Exception) = when (error) {
            is MapServiceException -> error.code in setOf(408, 429, 500, 502, 503, 504)
            is IOException -> true
            else -> false
        }
    }
}
