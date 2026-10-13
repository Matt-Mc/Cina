package dev.pocketmuse

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NotificationPermissionNotice() {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    val settings = context.getSharedPreferences("settings", 0)
    var enabled by remember { mutableStateOf(manager.areNotificationsEnabled()) }
    val owner = LocalLifecycleOwner.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enabled = manager.areNotificationsEnabled() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) enabled = manager.areNotificationsEnabled() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!enabled) {
        Text("Notifications are off. Items will be saved here, but this phone will not alert you.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && (!settings.getBoolean("notification_prompted", false) || (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
                settings.edit().putBoolean("notification_prompted", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text("Enable notifications") }
    }
}
