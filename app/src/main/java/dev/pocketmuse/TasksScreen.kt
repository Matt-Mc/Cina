package dev.pocketmuse

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun TasksScreen(vm: AssistantViewModel, tab: Int, onTab: (Int) -> Unit, onOpenChat: () -> Unit, onOpenResult: (String,Long) -> Unit,
    openKind: String? = null, openId: String? = null, onConsumed: () -> Unit = {}, entryKey: Int = 0) {
    var focusKind by rememberSaveable(entryKey) { mutableStateOf<String?>(null) }
    var focusId by rememberSaveable(entryKey) { mutableStateOf<String?>(null) }
    var filters by rememberSaveable(entryKey) { mutableStateOf(false) }
    fun open(kind: String,id: String) { focusKind=kind;focusId=id;onTab(when(kind) { "reminder"->1;"schedule"->2;"action"->3;else->4 }) }
    fun overview() { focusKind=null;focusId=null;filters=false;onTab(4) }
    LaunchedEffect(openKind,openId) { if(openKind!=null && openId!=null) { open(openKind,openId);onConsumed() } }
    BackHandler(focusId!=null || tab!=4) { overview() }
    LaunchedEffect(Unit) { while (true) { vm.refresh();delay(5_000) } }
    Page("Work", "Reminders, scheduled prompts and approved actions.") {
        if(tab!=4 || focusId!=null) TextButton(onClick={ overview() }) { Text("Back to Work") }
        else TextButton(onClick={ filters=!filters }) { Text(if(filters) "Hide filters" else "All work & filters") }
        if(filters || tab!=4) ScreenTabs(listOf("Reminders", "Scheduled", "Actions"), (tab - 1).coerceIn(0, 2)) { focusId=null;focusKind=null;onTab(it + 1) }
        when {
            tab==1 -> RemindersContent(vm,focusId)
            tab==2 -> ScheduledContent(vm,focusId)
            tab==3 -> DurableWorkContent(onOpenResult,focusId=focusId)
            else -> WorkOverview(vm,::open)
        }
    }
}

@Composable
private fun RemindersContent(vm: AssistantViewModel, focusId: String? = null) {
    val activity = checkNotNull(LocalActivity.current) as ComponentActivity
    val reminders by vm.reminders.collectAsState()
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var whenMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Reminder?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Upcoming reminders", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { editingId = null; title = ""; whenMillis = null; editorOpen = true }) { Text("+ New reminder") }
    }
    NotificationPermissionNotice()
    Text("Android battery settings can affect delivery.", color = Muted, fontSize = 12.sp)
    val open = reminders.filter { !it.done }
    if (open.isEmpty()) EmptyState("No reminders", "Add a reminder here or ask Cina to set one in chat.")
    (if (focusId!=null) reminders.filter { it.id.toString()==focusId } else if (showCompleted) reminders else open).forEach { reminder ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(reminder.done, onCheckedChange = { if (it) vm.completeReminder(reminder.id) else vm.undoReminder(reminder.id) }, modifier = Modifier.semantics { contentDescription = "Complete ${reminder.title}" })
            Column(Modifier.weight(1f)) {
                Text(reminder.title, color = Ink, fontWeight = FontWeight.Medium)
                Text(if (reminder.done) "Completed" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(reminder.whenMillis)), color = Muted, fontSize = 12.sp)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menu = true }) { Text("Options") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (!reminder.done) DropdownMenuItem(text = { Text("Edit") }, onClick = {
                        editingId = reminder.id; title = reminder.title; whenMillis = reminder.whenMillis; editorOpen = true; menu = false
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { deleting = reminder; menu = false })
                }
            }
        }
        HorizontalDivider(color = Line)
    }
    if (reminders.any { it.done }) TextButton(onClick = { showCompleted = !showCompleted }) { Text(if (showCompleted) "Hide completed" else "Show completed (${reminders.count { it.done }})") }
    if (editorOpen) AlertDialog(onDismissRequest = { editorOpen = false }, containerColor = Paper,
        title = { Text(if (editingId == null) "New reminder" else "Edit reminder") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NotificationPermissionNotice()
            OutlinedTextField(title, { title = it }, label = { Text("Remind me to…") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { chooseDateTime(activity, whenMillis ?: System.currentTimeMillis()) { whenMillis = it } }) {
                Text(whenMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Choose date & time")
            }
            if (whenMillis != null && whenMillis!! <= System.currentTimeMillis()) Text("Choose a future time.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(onClick = { if (vm.saveReminder(editingId, title, whenMillis!!)) editorOpen = false },
            enabled = title.isNotBlank() && whenMillis?.let { it > System.currentTimeMillis() } == true) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancel") } })
    deleting?.let { reminder -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this reminder?") },
        confirmButton = { TextButton(onClick = { vm.deleteReminder(reminder.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun ScheduledContent(vm: AssistantViewModel, focusId: String? = null) {
    val activity = checkNotNull(LocalActivity.current) as ComponentActivity
    val tasks by vm.scheduledTasks.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    val models by vm.models.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var modelPath by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var whenMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var repeat by rememberSaveable { mutableStateOf("once") }
    var viewed by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    var openedFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusId,tasks) {
        if(focusId!=null && openedFocus!=focusId) tasks.firstOrNull { it.id.toString()==focusId }?.let { task ->
            openedFocus=focusId
            if(task.lastResult!=null || task.lastError!=null) viewed=task
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Let Cina run a prompt later", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { editingId = null; title = ""; prompt = ""; whenMillis = null; repeat = "once"; modelPath = selectedModel.orEmpty(); adding = true }) { Text("+ Schedule") }
    }
    NotificationPermissionNotice()
    Text("Runs on this phone in the background. Proposed notes and reminders wait for review in Actions. Android may start prompts late. Connected services are unavailable.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    if (tasks.isEmpty()) EmptyState("No scheduled prompts", "Schedule a one-time, daily, or weekly prompt. Cina saves the result and notifies you when it is ready.")
    tasks.filter { focusId==null || it.id.toString()==focusId }.forEach { task ->
        Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("Options") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (task.enabled || task.repeat != "once" || task.nextRunMillis > System.currentTimeMillis()) DropdownMenuItem(text = { Text(if (task.enabled) "Pause" else "Resume") },
                            onClick = { vm.setScheduledTaskEnabled(task, !task.enabled); menu = false })
                        DropdownMenuItem(text = { Text("Edit schedule") }, enabled = !task.running, onClick = { editingId = task.id; title = task.title; prompt = task.prompt; whenMillis = task.nextRunMillis; repeat = task.repeat; modelPath = task.modelPath; adding = true; menu = false })
                        DropdownMenuItem(text = { Text("Run now") }, enabled = !task.running, onClick = { vm.runScheduleNow(task.id); menu = false })
                        if(task.lastError != null) DropdownMenuItem(text = { Text("Retry failed run") }, enabled = !task.running, onClick = { vm.runScheduleNow(task.id, retry = true); menu = false })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { deleting = task; menu = false })
                    }
                }
            }
            Text(task.prompt, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when {
                task.running -> "Running on this phone"
                task.lastError != null -> "Last run failed${if (task.enabled) " · next ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(task.nextRunMillis))}" else " · needs attention"}"
                !task.enabled && task.repeat == "once" && task.lastResult != null -> "Completed"
                !task.enabled -> "Paused"
                task.nextRunMillis <= System.currentTimeMillis() -> "Waiting for Android to start"
                else -> "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(task.nextRunMillis))} · ${task.repeat}"
            }, color = Accent, fontSize = 12.sp)
            Text("Model: ${models.firstOrNull { it.path == task.modelPath }?.name ?: "Unavailable"}", color = Muted, fontSize = 11.sp)
            if (task.lastRunMillis != null) Text("Last run ${if (task.lastError == null) "completed" else "failed"}", color = Muted, fontSize = 12.sp)
            if (task.lastResult != null || task.lastError != null) TextButton(onClick = { viewed = task }) { Text("View result") }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, containerColor = Paper, title = { Text(if(editingId == null) "Schedule a prompt" else "Edit schedule") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(prompt, { prompt = it }, label = { Text("What should Cina do?") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
            OutlinedButton(onClick = { chooseDateTime(activity, whenMillis ?: System.currentTimeMillis()) { whenMillis = it } }) {
                Text(whenMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Choose date & time")
            }
            ScreenTabs(listOf("Once", "Daily", "Weekly"), listOf("once", "daily", "weekly").indexOf(repeat)) { repeat = listOf("once", "daily", "weekly")[it] }
            NotificationPermissionNotice()
            if(whenMillis != null && whenMillis!! <= System.currentTimeMillis()) Text("Choose a future time.", color = MaterialTheme.colorScheme.error)
            Text("Model for this task", color = Ink)
            if(models.isEmpty()) Text("Install a model in Settings → Models first.", color = Muted)
            models.forEach { model -> FilterChip(selected = modelPath == model.path, onClick = { modelPath = model.path }, label = { Text(model.name) }) }
            Text("Local tools only. Run now and Retry preserve the regular schedule.", color = Muted)
        } },
        confirmButton = { TextButton(onClick = {
            if (vm.saveSchedule(editingId, title, prompt, modelPath, whenMillis!!, repeat)) { adding = false; title = ""; prompt = ""; whenMillis = null; repeat = "once" }
        }, enabled = title.isNotBlank() && prompt.isNotBlank() && whenMillis?.let { it > System.currentTimeMillis() } == true && models.any { it.path == modelPath }) { Text("Save schedule") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    viewed?.let { task -> AlertDialog(onDismissRequest = { viewed = null }, containerColor = Paper, title = { Text(task.title) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (task.lastError != null) Text(task.lastError, color = MaterialTheme.colorScheme.error)
            if (task.lastResult != null) AssistantResponse(task.lastResult)
        } }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Done") } }) }
    deleting?.let { task -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${task.title}?") },
        confirmButton = { TextButton(onClick = { vm.deleteScheduledTask(task.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
