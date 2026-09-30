package dev.pocketmuse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal object ScheduledTaskScheduler {
    fun schedule(context: Context, task: ScheduledTask) {
        if (!task.enabled) return
        val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(workDataOf("task_id" to task.id, "run_ms" to task.nextRunMillis))
            .setInitialDelay((task.nextRunMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(tag(task.id)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("${tag(task.id)}-${task.nextRunMillis}", ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, id: Long) { WorkManager.getInstance(context).cancelAllWorkByTag(tag(id)) }
    private fun tag(id: Long) = "cina-scheduled-$id"

    fun nextRun(previous: Long, repeat: String): Long? {
        val days = when (repeat) { "daily" -> 1L; "weekly" -> 7L; else -> return null }
        var next = Instant.ofEpochMilli(previous).atZone(ZoneId.systemDefault())
        do { next = next.plusDays(days) } while (next.toInstant().toEpochMilli() <= System.currentTimeMillis())
        return next.toInstant().toEpochMilli()
    }
}

class ScheduledTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("task_id", -1)
        val scheduledFor = inputData.getLong("run_ms", -1)
        val store = LocalStore(applicationContext)
        try {
            val task = store.scheduledTask(id) ?: return Result.success()
            if (!task.enabled || task.nextRunMillis != scheduledFor) return Result.success()
            val outcome = runCatching {
                val model = store.models().firstOrNull { it.path == task.modelPath }
                    ?: error("The selected model is no longer on this phone.")
                ModelAccess.mutex.withLock {
                    val tools = AssistantTools(applicationContext, store, scheduledModelPath = task.modelPath)
                    val runtime = AssistantRuntime(applicationContext, store) { webEnabled -> tools.specification(webEnabled) }
                    val chat = Chat(0, task.title, false, false)
                    try {
                        withTimeout(8 * 60 * 1000L) {
                            runtime.use(model, chat,
                                applicationContext.getSharedPreferences("settings", 0).getBoolean("memory", true),
                                YouProfileStore(applicationContext).read())
                            var answer = splitModelResponse(runtime.generate(task.prompt) {}).answer
                            repeat(3) {
                                val request = tools.parse(answer) ?: return@withTimeout answer.takeIf { it.isNotBlank() }
                                    ?: error("The model did not produce a text answer.")
                                val result = try { tools.execute(chat, request) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { "Tool error: ${e.message}" }
                                answer = splitModelResponse(runtime.generate(
                                    AssistantPrompt.toolFollowUp(task.prompt, request.name, result)
                                ) {}).answer
                            }
                            require(tools.parse(answer) == null) { "The scheduled task reached its tool limit." }
                            answer.takeIf { it.isNotBlank() } ?: error("The model did not produce a text answer.")
                        }
                    } finally { runtime.release() }
                }
            }
            if (outcome.exceptionOrNull() is CancellationException) throw outcome.exceptionOrNull()!!
            val next = ScheduledTaskScheduler.nextRun(scheduledFor, task.repeat)
            val error = outcome.exceptionOrNull()?.message?.take(300) ?: if (outcome.isFailure) "The model could not run." else null
            store.finishScheduledTask(id, scheduledFor, outcome.getOrNull(), error, next)
            TodayWidget.updateAll(applicationContext)
            val updated = store.scheduledTask(id)
            if (updated?.enabled == true && updated.nextRunMillis == next) ScheduledTaskScheduler.schedule(applicationContext, updated)
            showNotification(task, outcome.getOrNull(), error)
            return Result.success()
        } finally { store.close() }
    }

    private fun showNotification(task: ScheduledTask, answer: String?, error: String?) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("scheduled_tasks", "Scheduled tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, task.id.toInt(),
            Intent(applicationContext, MainActivity::class.java).putExtra("open_scheduled_tasks", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(applicationContext, "scheduled_tasks")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (answer != null) task.title else "${task.title} could not run")
            .setContentText((answer ?: error ?: "Open Cina for details.").take(120))
            .setContentIntent(open).setAutoCancel(true).build()
        try { manager.notify((task.id xor 0x40000000L).toInt(), notification) } catch (_: SecurityException) { }
    }
}
