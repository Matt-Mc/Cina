package dev.pocketmuse

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal object DurableWorkScheduler {
    fun ensureRecovery(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("cina-work-recovery",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DurableWorkWorker>(15,TimeUnit.MINUTES).build()).result.get(30,TimeUnit.SECONDS)
    }
    fun recover(context: Context) {
        val manager=WorkManager.getInstance(context)
        // Persist a sweep before approvals can be created, covering a crash between DB commit and enqueue.
        manager.enqueueUniquePeriodicWork("cina-work-recovery",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DurableWorkWorker>(15,TimeUnit.MINUTES).build())
        manager.enqueueUniqueWork("cina-work-sweep",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<DurableWorkWorker>().build())
    }
    fun enqueue(context: Context,task: DurableTask) {
        WorkManager.getInstance(context).enqueueUniqueWork("cina-work-${task.id}-${task.revision}",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DurableWorkWorker>().setInputData(workDataOf("work_id" to task.id))
                .setInitialDelay(((task.approvalFrom ?: task.runAt ?: 0)-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS).build())
    }
}

class DurableWorkWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            LocalStore(applicationContext).use { store ->
                val coordinator=WorkCoordinator(store)
                val id=inputData.getString("work_id")
                val queued=coordinator.tasks().filter { it.state=="queued" }
                if(id==null) queued.filter { (it.approvalFrom ?: 0)>System.currentTimeMillis() }.forEach { DurableWorkScheduler.enqueue(applicationContext,it) }
                val ids=if(id!=null) listOf(id) else queued.filter { (it.approvalFrom ?: 0)<=System.currentTimeMillis() }.take(50).map { it.id }
                ids.forEach { workId ->
                    for(step in 0 until WorkCoordinator.MAX_STEPS) {
                        if(isStopped) return@withContext Result.retry()
                        val result=coordinator.execute(workId) ?: break
                        if(result.state!="queued" || (result.approvalFrom ?: 0)>System.currentTimeMillis()) break
                    }
                }
                // Alarm registration is outside the transaction; repeated recovery does not create another reminder.
                ReminderReceiver.recover(applicationContext)
                WorkFollowUps.recover(applicationContext,coordinator.tasks())
                if(id==null && coordinator.tasks().any { it.state=="queued" && (it.approvalFrom ?: 0)<=System.currentTimeMillis() }) Result.retry() else Result.success()
            }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { Result.retry() }
    }
}
