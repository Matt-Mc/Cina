package dev.pocketmuse

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        deliver(context, intent.getLongExtra("id", -1))
    }
    companion object {
        private const val CHANNEL_ID = "reminders"
        private const val RECOVERY_WINDOW_MS = 24 * 60 * 60 * 1000L

        fun recover(context: Context) {
            val now = System.currentTimeMillis()
            LocalStore(context).use { store ->
                store.reminders().filter { !it.done && it.notifiedAt == null }.forEach { reminder ->
                    if (reminder.whenMillis > now) schedule(context, reminder.id, reminder.whenMillis)
                    else if (now - reminder.whenMillis <= RECOVERY_WINDOW_MS) deliver(context, reminder.id)
                }
            }
        }

        private fun deliver(context: Context, id: Long) {
            if (id < 0) return
            LocalStore(context).use { store ->
                val reminder = store.reminders().firstOrNull { it.id == id }
                    ?.takeIf { !it.done && it.notifiedAt == null && it.whenMillis <= System.currentTimeMillis() }
                    ?: return
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
                if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE ||
                    (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
                if (!store.claimReminderNotification(id)) return
                val open = PendingIntent.getActivity(context, id.toInt(),
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET_PAGE, "Reminders")
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(reminder.title).setContentText("Cina reminder")
                    .setContentIntent(open).setAutoCancel(true).build()
                try { manager.notify(id.toInt(), notification) }
                catch (e: SecurityException) {
                    store.clearReminderNotification(id)
                    Log.w("CinaReminders", "Notification permission was denied", e)
                }
            }
        }

        fun schedule(context: Context, id: Long, whenMillis: Long) {
            val intent = Intent(context, ReminderReceiver::class.java).putExtra("id", id)
            val pending = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val alarms = context.getSystemService(AlarmManager::class.java)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
        }
        fun cancel(context: Context, id: Long) {
            val intent = Intent(context, ReminderReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) {
                context.getSystemService(AlarmManager::class.java).cancel(pending)
                pending.cancel()
            }
        }
    }
}
