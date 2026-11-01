package dev.pocketmuse

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.TimeUnit

/** One area at a time, with one delayed retry for a transient failure. */
class CollectionDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val id = requireNotNull(inputData.getString("collection_id"))
            val title = requireNotNull(inputData.getString("title"))
            val point = GeoPoint(inputData.getDouble("lat", Double.NaN), inputData.getDouble("lon", Double.NaN))
            val radius = inputData.getInt("radius", 0)
            setProgress(workDataOf("message" to if (runAttemptCount == 0) "Requesting map records…" else "Retrying map service…"))
            val bytes = EssentialsDownload().fetch(point, radius) { setProgress(workDataOf("message" to it)) }
            currentCoroutineContext().ensureActive()
            setProgress(workDataOf("message" to "Checking and saving places…"))
            val snapshot = Essentials.parse(bytes.toString(Charsets.UTF_8), point, radius)
            val coroutine = currentCoroutineContext()
            CollectionRepository(applicationContext).use { it.install(id, title, point, radius, snapshot, bytes.size.toLong()) { coroutine.ensureActive() } }
            Result.success(workDataOf("message" to "Saved ${snapshot.places.size} places for offline use."))
        } catch (e: Exception) {
            // A request deadline is retryable; user/WorkManager cancellation never retries or commits.
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            currentCoroutineContext().ensureActive()
            if (runAttemptCount < 1 && EssentialsDownload.retryable(e)) Result.retry()
            else {
                val message = when (e) {
                    is java.net.SocketTimeoutException, is TimeoutCancellationException -> "The map service timed out after retrying. Try later or choose a smaller area. Any saved area is unchanged."
                    is MapServiceException -> "Map service unavailable (HTTP ${e.code}). Try later; your saved area is unchanged."
                    is java.io.IOException -> "The download was interrupted. Check your connection and retry. Any saved area is unchanged."
                    else -> e.message ?: "Download failed. Retry later."
                }
                Result.failure(workDataOf("message" to message.take(500)))
            }
        }
    }
    companion object {
        const val MAX_BYTES = 12 * 1024 * 1024
        const val TAG = "collection-download"
        internal fun enqueue(context: Context, id: String, title: String, point: GeoPoint, radius: Int) {
            require(title.isNotBlank() && title.length <= 160 && radius in 1..20)
            val request = OneTimeWorkRequestBuilder<CollectionDownloadWorker>()
                .setInputData(workDataOf("collection_id" to id, "title" to title, "lat" to point.lat, "lon" to point.lon, "radius" to radius))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
