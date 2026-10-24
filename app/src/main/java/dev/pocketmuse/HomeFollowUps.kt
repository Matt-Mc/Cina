package dev.pocketmuse

import android.app.*
import android.content.Context
import android.content.Intent

internal object HomeFollowUps {
    fun recover(context: Context, jobs: List<HomeJob>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(WorkFollowUps.CHANNEL, "Work follow-ups", NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled() || manager.getNotificationChannel(WorkFollowUps.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val prefs = context.getSharedPreferences("home_notifications", 0)
        jobs.filter { it.state in setOf("completed", "failed", "interrupted") }.forEach { job ->
            if(prefs.getString(job.id, null) == job.state) return@forEach
            val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET_PAGE, "Work")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open = PendingIntent.getActivity(context, 7302, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, WorkFollowUps.CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if(job.state == "completed") "Home work completed" else "Home work needs attention")
                .setContentText("Open Work to review progress and results.").setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
            try { manager.notify("home:${job.id}", 0, notification); prefs.edit().putString(job.id, job.state).commit() }
            catch (_: SecurityException) { }
        }
    }
}
