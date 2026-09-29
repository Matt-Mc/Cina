package dev.pocketmuse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class WidgetInputActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODE = "widget_mode"
        const val MODE_NOTE = "note"
        const val MODE_ASK = "ask"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noteMode = intent.getStringExtra(EXTRA_MODE) == MODE_NOTE
        setContent {
            MaterialTheme {
                WidgetInput(noteMode, onCancel = ::finish, onSubmit = { content ->
                    if (noteMode) {
                        val body = content.trim()
                        val title = body.lineSequence().first().take(80).ifBlank { "Quick note" }
                        LocalStore(this).use { it.addNote(title, body) }
                        finish()
                    } else {
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_WIDGET_PROMPT, content.trim())
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                })
            }
        }
    }
}

@Composable
private fun WidgetInput(noteMode: Boolean, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var content by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(150); focus.requestFocus(); keyboard?.show() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(if (noteMode) "Quick note" else "Ask Cina", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = content, onValueChange = { content = it }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            placeholder = { Text(if (noteMode) "What's on your mind?" else "Ask anything…") },
            minLines = if (noteMode) 4 else 1, maxLines = if (noteMode) 8 else 4,
            keyboardOptions = KeyboardOptions(imeAction = if (noteMode) ImeAction.Default else ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (content.isNotBlank()) onSubmit(content) })
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { onSubmit(content) }, enabled = content.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (noteMode) "Save note" else "Send to Cina")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
