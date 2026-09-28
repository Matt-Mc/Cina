package dev.pocketmuse

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val title = intent.getStringExtra("title") ?: "Reminder"
        val notification = android.app.Notification.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title)
            .setContentText("Cina reminder").setAutoCancel(true).build()
        try { manager.notify(intent.getLongExtra("id", 0).toInt(), notification) } catch (_: SecurityException) { }
    }
    companion object {
        fun schedule(context: Context, id: Long, title: String, whenMillis: Long) {
            val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("title", title)
            val pending = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarms = context.getSystemService(AlarmManager::class.java)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
        }
    }
}
