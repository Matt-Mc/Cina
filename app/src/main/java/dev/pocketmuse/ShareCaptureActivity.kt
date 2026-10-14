package dev.pocketmuse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

class ShareCaptureActivity : ComponentActivity() {
    private val vm: InboxViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.captureShare(intent)
        setContent {
            val message by vm.message.collectAsState()
            val busy by vm.busy.collectAsState()
            val saved by vm.saved.collectAsState()
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Paper, onBackground = Ink)) {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = Paper) {
                    Page("Cina Inbox", "A place for things you want to return to.") {
                        if (busy) CircularProgressIndicator()
                        Text(message)
                        if (saved) Button(onClick = {
                            startActivity(Intent(this@ShareCaptureActivity, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_WIDGET_PAGE, "Inbox")
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            finish()
                        }) { Text("Open Inbox") }
                        TextButton(onClick = { finish() }, enabled = !busy) { Text(if (saved) "Done" else "Close") }
                    }
                }
            }
        }
    }
}
