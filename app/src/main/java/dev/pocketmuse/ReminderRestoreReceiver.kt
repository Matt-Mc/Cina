package dev.pocketmuse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = LocalStore(context)
        try { store.reminders().filter { !it.done && it.whenMillis > System.currentTimeMillis() }.forEach { ReminderReceiver.schedule(context, it.id, it.title, it.whenMillis) } }
        finally { store.close() }
    }
}
