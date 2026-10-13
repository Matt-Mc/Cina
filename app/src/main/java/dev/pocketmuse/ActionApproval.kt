package dev.pocketmuse

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun ActionApprovalDialog(action: PendingAction, itemLabel: String?, before: Note?,
    validate: (ToolRequest) -> String?, onReject: () -> Unit, onApprove: (ToolRequest, Boolean) -> Unit) {
    val request = action.request
    var json by rememberSaveable(request.name, request.arguments.toString()) { mutableStateOf(request.arguments.toString()) }
    var allowFuture by rememberSaveable { mutableStateOf(false) }
    var showBefore by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val arguments = remember(json) { JSONObject(json) }
    val edited = ToolRequest(request.name, arguments)
    val error = validate(edited)
    val label = ToolCatalog.get(request.name)?.label ?: "Review action"
    AlertDialog(onDismissRequest = {}, containerColor = Paper, title = { Text(label) },
        text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Review the details. You can change them before continuing.", color = Muted)
            if (itemLabel != null) Text("Item: $itemLabel", color = Ink)
            if (request.name == "mcp_call") {
                Text("Service: ${arguments.optString("server")}\nAction: ${arguments.optString("tool").replace('_', ' ')}", color = Ink)
                Text("This service will receive every value shown below.", color = Muted)
            }
            if (before != null) {
                TextButton(onClick = { showBefore = !showBefore }) { Text(if (showBefore) "Hide current note" else "Read current note") }
                if (showBefore) Text(before.body, color = Ink)
                if (arguments.has("body")) Text("The new text below replaces the entire note.", color = MaterialTheme.colorScheme.error)
                if (arguments.has("append")) Text("The text below will be added. Existing text stays unchanged.", color = Muted)
            }
            val fields = (arguments.keys().asSequence().toList() + ToolCatalog.get(request.name)?.fields.orEmpty().filter { it.required }.map { it.name }).distinct()
            fields.filter { it !in setOf("id", "version", "server", "tool") }.forEach { key ->
                ArgumentEditor(key, arguments.opt(key) ?: "") { value ->
                    json = JSONObject(json).apply { put(key, value) }.toString()
                }
            }
            if (request.name in setOf("create_reminder", "reschedule_reminder", "create_scheduled_task")) {
                val activity = LocalActivity.current as? ComponentActivity
                if (!arguments.has("when_iso") && !arguments.has("when_text")) Text("Choose when this should happen.", color = Muted)
                OutlinedButton(onClick = { activity?.let { chooseDateTime(it) { time ->
                    json = JSONObject(json).apply { remove("when_text"); put("when_iso", java.time.Instant.ofEpochMilli(time).toString()) }.toString()
                } } }) { Text("Change date & time") }
                NotificationPermissionNotice()
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide action details" else "Action details") }
            if (showDetails) Text("${request.name}\n${arguments.toString(2)}", color = Muted)
            SettingToggle("Allow this action in future chats", "Applies to ${if (request.name == "mcp_call") "${arguments.optString("server")} · ${arguments.optString("tool")}" else label.lowercase()} with any content. Change this in Settings → Chat & permissions.", allowFuture) { allowFuture = it }
        } },
        confirmButton = { TextButton(onClick = { onApprove(edited, allowFuture) }, enabled = error == null) { Text(label) } },
        dismissButton = { TextButton(onClick = onReject) { Text("Cancel action") } })
}

@Composable
private fun ArgumentEditor(key: String, value: Any, onChange: (Any) -> Unit) {
    val label = when (key) { "when_iso", "when_text" -> "When"; "start_iso" -> "Starts"; "end_iso" -> "Ends"; "body" -> "Note text"; "append" -> "Text to add"; else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() } }
    when (value) {
        is JSONObject -> {
            Text(label, color = Ink)
            value.keys().asSequence().toList().forEach { child -> ArgumentEditor(child, value.get(child)) { changed -> onChange(JSONObject(value.toString()).put(child, changed)) } }
            if (value.length() == 0) Text("No additional details", color = Muted)
        }
        is JSONArray -> {
            Text(label, color = Ink)
            (0 until value.length()).forEach { index -> ArgumentEditor("Item ${index + 1}", value.get(index)) { changed -> onChange(JSONArray(value.toString()).put(index, changed)) } }
        }
        is Boolean -> SettingToggle(label, "", value) { onChange(it) }
        else -> {
            if (key.endsWith("_iso")) {
                val activity = LocalActivity.current as? ComponentActivity
                Text("$label: ${ReminderTime.label(value.toString())}", color = Ink)
                if (key != "when_iso") TextButton(onClick = { activity?.let { chooseDateTime(it) { time -> onChange(java.time.Instant.ofEpochMilli(time).toString()) } } }) { Text("Change ${label.lowercase()}") }
            } else OutlinedTextField(if (value == JSONObject.NULL) "" else value.toString(), { text ->
                if (value is Number) text.toLongOrNull()?.let { onChange(it) } else onChange(text)
            }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = if (key in setOf("body", "append", "text", "prompt", "instructions")) 3 else 1)
        }
    }
}
