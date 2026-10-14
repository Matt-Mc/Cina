package dev.pocketmuse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

internal object WorkFollowUps {
    const val CHANNEL="work_follow_ups"
    fun label(state: String): String? = when(state) {
        "completed" -> "Local work completed"
        "waiting_for_approval" -> "Local work needs approval"
        "waiting_for_input" -> "Local work needs your input"
        else -> null
    }
    /** Failed/disabled delivery stays eligible. Stable Android tags make a crash retry replace the alert. */
    @Synchronized
    fun deliver(tasks: List<DurableTask>,prefs: SharedPreferences,publish: (DurableTask,String)->Boolean) {
        tasks.forEach { task ->
            val title=label(task.state) ?: return@forEach
            val signature="${task.revision}:${task.state}"
            if(prefs.getString(task.id,null)!=signature && publish(task,title)) prefs.edit().putString(task.id,signature).commit()
        }
    }
    fun recover(context: Context,tasks: List<DurableTask>) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Work follow-ups",NotificationManager.IMPORTANCE_DEFAULT))
        if(!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE) return
        deliver(tasks,context.getSharedPreferences(CHANNEL,0)) { task,title ->
            val intent=Intent(context,MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET_PAGE,"Actions")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open=PendingIntent.getActivity(context,7301,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText("Open Tasks → Actions to review details and verified results.")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
            try { manager.notify("work:${task.id}",0,notification);true } catch(_: SecurityException) { false }
        }
        tasks.filter { label(it.state)==null }.forEach { manager.cancel("work:${it.id}",0) }
    }
}
