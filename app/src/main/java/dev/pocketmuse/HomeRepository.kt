package dev.pocketmuse

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Upgrade compatibility only. Never connects to the retired Home service. */
internal object HomeRepository {
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS home_jobs(id TEXT PRIMARY KEY,server TEXT NOT NULL,device TEXT NOT NULL,chat_id INTEGER,body TEXT NOT NULL,snapshot TEXT NOT NULL,submitted INTEGER NOT NULL DEFAULT 0,command TEXT,error TEXT NOT NULL DEFAULT '',delivered INTEGER NOT NULL DEFAULT 0)")
    }
    fun schedule(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("home_reconcile")
        WorkManager.getInstance(context).cancelUniqueWork("home_sync")
    }
}

// WorkManager may instantiate an old persisted job before the first activity opens.
internal class HomeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
