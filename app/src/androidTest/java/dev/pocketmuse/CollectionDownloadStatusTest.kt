package dev.pocketmuse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CollectionDownloadStatusTest {
    @Test fun activeRequestWinsOverSavedHistoryAndCancellationKeepsItsIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context)
        val preferences = context.getSharedPreferences(CollectionDownloadWorker.TAG, Context.MODE_PRIVATE)
        val previous = preferences.getString("request_id", null)
        val request = OneTimeWorkRequestBuilder<CollectionDownloadWorker>()
            .setInitialDelay(1, TimeUnit.HOURS).build()
        try {
            work.enqueueUniqueWork(CollectionDownloadWorker.TAG, ExistingWorkPolicy.KEEP, request).result.get()
            preferences.edit().putString("request_id", "stale-history-id").commit()
            assertEquals(request.id, CollectionDownloadWorker.currentWork(context)?.id)
            preferences.edit().putString("request_id", request.id.toString()).commit()
            work.cancelUniqueWork(CollectionDownloadWorker.TAG).result.get()
            val cancelled = CollectionDownloadWorker.currentWork(context)
            assertEquals(request.id, cancelled?.id)
            assertEquals(WorkInfo.State.CANCELLED, cancelled?.state)
            preferences.edit().putString("request_id", "unknown-id").commit()
            assertNull(CollectionDownloadWorker.currentWork(context))
        } finally {
            work.cancelWorkById(request.id).result.get()
            preferences.edit().putString("request_id", previous).commit()
        }
    }
}
