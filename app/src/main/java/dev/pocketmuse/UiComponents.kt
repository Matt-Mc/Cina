package dev.pocketmuse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

internal val Paper = Color(0xFFFAF9F6)
internal val Ink = Color(0xFF26312C)
internal val Muted = Color(0xFF767E78)
internal val Line = Color(0xFFE7E9E2)
internal val Soft = Color(0xFFF0F2EC)
internal val Accent = Color(0xFF4F7562)
internal val Warm = Color(0xFFF4F0E9)

@Composable
internal fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 31.sp, color = Ink)
        if(subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun SectionTitle(text: String) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink) }

@Composable
internal fun PaperRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Spacer(Modifier.width(8.dp)); TextButton(onClick = onClick) { Text(action) }
    }
    HorizontalDivider(color = Line)
}


@Composable
internal fun NavigationRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(18.dp)).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text("›", color = Muted, fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun SettingToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ScreenTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Surface(color = if (index == selected) Ink else Color.Transparent, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).selectable(selected = index == selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(index) })) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (index == selected) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 23.sp, color = Ink)
        Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

internal fun chooseDateTime(activity: ComponentActivity, initial: Long = System.currentTimeMillis(), onChosen: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initial }
    android.app.DatePickerDialog(activity, { _, year, month, day ->
        android.app.TimePickerDialog(activity, { _, hour, minute ->
            onChosen(Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(activity)).show()
    }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
internal fun ActionApprovalDialog(action: PendingAction, onReject: () -> Unit, onApprove: () -> Unit, onAlwaysAllow: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val request = action.request
    val title = when (request.name) {
        "create_note" -> "Save this note?"
        "edit_note" -> "Update this note?"
        "delete_note" -> "Delete this note?"
        "create_reminder" -> "Set this reminder?"
        "complete_reminder" -> "Complete this reminder?"
        "reschedule_reminder" -> "Reschedule this reminder?"
        "create_scheduled_task" -> "Schedule this prompt?"
        "create_calendar_event" -> "Open this calendar event?"
        "set_alarm" -> "Set this alarm?"
        "share_text" -> "Share this text?"
        "mcp_call" -> "Run this connected action?"
        else -> "Let Cina do this?"
    }
    val arguments = request.arguments
    AlertDialog(onDismissRequest = onReject, containerColor = Paper, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (request.name == "mcp_call") {
                Text("${arguments.optString("server")} · ${arguments.optString("tool").replace('_', ' ')}", color = Ink)
                Text("This service will receive the action's arguments.", color = Muted, fontSize = 12.sp)
            }
            val readable = listOf("title", "body", "prompt", "text", "when_iso", "start_iso", "end_iso", "repeat", "message", "hour", "minute", "id")
                .mapNotNull { key -> arguments.optString(key).takeIf { it.isNotBlank() }?.let { key to it } }
            readable.forEach { (key, value) ->
                Text(when (key) { "when_iso" -> "When"; "start_iso" -> "Starts"; "end_iso" -> "Ends"; "id" -> "Item ID"; else -> key.replaceFirstChar { it.uppercase() } }, color = Muted, fontSize = 11.sp)
                Text(value.take(800), color = Ink, fontSize = 14.sp)
            }
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "View action details") }
            if (details) Text("${request.name}\n${arguments.toString(2)}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = onAlwaysAllow) { Text("Always allow this tool", fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = onApprove) { Text("Allow once") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Not now") } })
}
