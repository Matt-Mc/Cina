package dev.pocketmuse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date

private fun openInput(context: Context, mode: String, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(context, requestCode,
        Intent(context, WidgetInputActivity::class.java).putExtra(WidgetInputActivity.EXTRA_MODE, mode),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

private fun openPage(context: Context, page: String, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET_PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

class QuickNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_quick_note)
            views.setOnClickPendingIntent(R.id.quick_note, openInput(context, WidgetInputActivity.MODE_NOTE, 101))
            manager.updateAppWidget(id, views)
        }
    }
}

class AskCinaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_ask_cina)
            views.setOnClickPendingIntent(R.id.ask_cina, openInput(context, WidgetInputActivity.MODE_ASK, 102))
            manager.updateAppWidget(id, views)
        }
    }
}

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val store = LocalStore(context)
            val reminders: List<Reminder>
            val latestTask: ScheduledTask?
            try {
                reminders = store.reminders().filter { !it.done }.take(2)
                latestTask = store.scheduledTasks().filter { it.lastRunMillis != null }
                    .maxByOrNull { it.lastRunMillis ?: 0L }
            } finally { store.close() }
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_today)
                views.setTextViewText(R.id.today_reminders,
                    if (reminders.isEmpty()) "No open reminders"
                    else reminders.joinToString("\n") { reminder ->
                        val whenText = if (reminder.whenMillis < now) "Overdue" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(reminder.whenMillis))
                        "$whenText  ·  ${reminder.title}"
                    })
                views.setTextViewText(R.id.today_task,
                    latestTask?.let { task ->
                        "Latest task: ${task.title} · ${(task.lastResult ?: task.lastError ?: "Finished").replace('\n', ' ').take(85)}"
                    } ?: "No task results yet")
                views.setOnClickPendingIntent(R.id.today_reminders, openPage(context, "Notes", 103))
                views.setOnClickPendingIntent(R.id.today_task, openPage(context, "Scheduled tasks", 104))
                views.setOnClickPendingIntent(R.id.today_title, openPage(context, "Notes", 105))
                manager.updateAppWidget(id, views)
            }
        }
    }
}
