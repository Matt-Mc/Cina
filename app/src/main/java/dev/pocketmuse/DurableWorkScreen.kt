package dev.pocketmuse

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DurableWorkContent(onOpenResult: (String)->Unit,vm: DurableWorkViewModel=viewModel()) {
    val tasks by vm.tasks.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val events by vm.events.collectAsState()
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DurableTask?>(null) }
    var approval by remember { mutableStateOf<DurableTask?>(null) }
    var history by remember { mutableStateOf<DurableTask?>(null) }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { while(true) { vm.refresh();delay(1500) } }
    Text("Build up to eight ordered local steps. Review every remaining step and its start time for one approved run. Completed steps never run again.",color=Muted)
    Button(enabled=!busy,onClick={ editing=null;editor=true }) { Text("New local task") }
    if(message.isNotBlank()) Text(message,color=Ink)
    if(tasks.isEmpty()) EmptyState("Ready when you are","Create a local task here, or turn a kept Inbox action into a task.")
    tasks.filter { showFinished || it.state !in setOf("completed","cancelled") }.forEach { task ->
        Surface(color=Soft,shape=MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SectionTitle(task.action.title)
            Text("${task.steps.count { it.state=="completed" }} of ${task.steps.size} steps complete · ${workState(task.state)}",color=Accent)
            Text(task.message,color=Muted)
            task.runAt?.let { Text("Requested start: ${DateFormat.getDateTimeInstance().format(Date(it))}",color=Muted) }
            task.approvalUntil?.let { Text("Approval expires ${DateFormat.getDateTimeInstance().format(Date(it))}",color=Muted) }
            task.steps.forEach { step -> Text("${step.ordinal+1}. ${step.action.title} · ${if(step.state=="completed") "Verified ${step.action.kind} #${step.resultId}" else "Not yet run"}",color=Muted) }
            if(task.state !in setOf("completed","cancelled")) {
                Row {
                    if(task.state=="waiting_for_approval") TextButton(enabled=!busy,onClick={ approval=task }) { Text("Review action") }
                    TextButton(enabled=!busy,onClick={ editing=task;editor=true }) { Text("Edit task") }
                    TextButton(enabled=!busy,onClick={ vm.control(task,if(task.state=="paused") "resume" else "pause") }) { Text(if(task.state=="paused") "Resume" else "Pause") }
                }
                TextButton(enabled=!busy,onClick={ vm.control(task,"cancel") }) { Text("Cancel task") }
            }
            if(task.steps.any { it.state=="completed" }) {
                Text("Verified results remain if later steps stop. Later edits or deletion do not rerun completed steps.",color=Muted)
                task.steps.filter { it.state=="completed" }.map { it.action.kind }.distinct().forEach { kind ->
                    TextButton(onClick={ onOpenResult(kind) }) { Text(if(kind=="note") "Open Notes" else "Open Reminders") }
                }
            }
            TextButton(onClick={ history=task;vm.history(task) }) { Text("Task history") }
        } }
    }
    if(tasks.any { it.state in setOf("completed","cancelled") }) TextButton(onClick={ showFinished=!showFinished }) { Text(if(showFinished) "Hide finished tasks" else "Show finished tasks") }
    if(editor) WorkTaskEditor(editing,null,{ editor=false }) { actions,runAt -> vm.savePlan(actions,runAt,editing);editor=false }
    approval?.let { task -> AlertDialog(onDismissRequest={ approval=null },title={ Text("Approve this local action?") },text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(task.runAt?.takeIf { it>System.currentTimeMillis() }?.let { "Start no earlier than ${DateFormat.getDateTimeInstance().format(Date(it))} (${java.util.TimeZone.getDefault().id})." } ?: "Start after approval.")
            task.steps.forEach { step ->
                if(step.state=="completed") Text("Step ${step.ordinal+1}: already completed; will not repeat.",color=Muted)
                else {
                    Text("Step ${step.ordinal+1}: ${if(step.action.kind=="note") "Create one note in Cina → Notes" else "Create one reminder in Cina → Work → Reminders"}")
                    Text("Title: ${step.action.title}")
                    if(step.action.kind=="note") Text(step.action.body)
                    else Text("When: ${DateFormat.getDateTimeInstance().format(Date(requireNotNull(step.action.whenMillis)))} (${java.util.TimeZone.getDefault().id})")
                }
            }
            if(task.steps.any { it.action.kind=="reminder" && it.state!="completed" }) { NotificationPermissionNotice();Text("Completion means reminders were saved, not that notifications were delivered.") }
            Text("Approve these remaining steps and start time once. The run window lasts 15 minutes from the chosen start, or from approval if already due. Editing, pausing or cancelling revokes it. Completed results remain if later steps stop.")
        }
    },confirmButton={ TextButton(enabled=!busy,onClick={ vm.approve(task);approval=null }) { Text("Approve once") } },dismissButton={ TextButton(onClick={ vm.control(task,"cancel");approval=null },enabled=!busy) { Text("Deny action") } }) }
    history?.let { task -> AlertDialog(onDismissRequest={ history=null },title={ Text("Task history") },text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            events.forEach { Text("${workState(it.state)} · ${DateFormat.getDateTimeInstance().format(Date(it.time))}\n${it.detail}") }
        }
    },confirmButton={ TextButton(onClick={ history=null }) { Text("Done") } }) }
}

private val WorkActionsSaver=Saver<List<WorkAction>,String>(
    save={ actions -> org.json.JSONArray().apply { actions.forEach { put(org.json.JSONObject().put("kind",it.kind).put("title",it.title).put("body",it.body).put("time",it.whenMillis ?: org.json.JSONObject.NULL)) } }.toString() },
    restore={ raw -> org.json.JSONArray(raw).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { j -> WorkAction(j.getString("kind"),j.getString("title"),j.getString("body"),if(j.isNull("time")) null else j.getLong("time")) } } } }
)

@Composable
internal fun WorkTaskEditor(task: DurableTask?,proposal: IntakeProposal?,onDismiss: ()->Unit,onSave: (List<WorkAction>,Long?)->Unit) {
    var actions by rememberSaveable(task?.id,proposal?.id,stateSaver=WorkActionsSaver) { mutableStateOf(task?.steps?.map { it.action } ?: listOf(WorkAction("note",proposal?.suggestion?.text?.take(160).orEmpty(),proposal?.suggestion?.text.orEmpty()))) }
    var selected by rememberSaveable(task?.id,proposal?.id) { mutableIntStateOf(task?.steps?.count { it.state=="completed" } ?: 0) }
    var runAt by rememberSaveable(task?.id,proposal?.id) { mutableStateOf(task?.runAt) }
    val completed=task?.steps?.count { it.state=="completed" } ?: 0
    val index=selected.coerceIn(0,actions.lastIndex)
    val action=actions[index]
    val editable=index>=completed
    val activity=LocalActivity.current as? ComponentActivity
    fun update(value: WorkAction) { actions=actions.mapIndexed { i,old -> if(i==index) value else old } }
    val valid=runCatching {
        val now=System.currentTimeMillis()
        require(runAt==null || runAt!!>now && runAt!!<=now+365L*86400000)
        actions.drop(completed).forEach { it.validate(maxOf(now,runAt ?: now)) }
    }.isSuccess
    AlertDialog(onDismissRequest=onDismiss,title={ Text(if(task==null) "Create a local task" else "Edit task") },text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Save a plan for review. Each step runs after the previous step is verified; up to eight steps.")
            proposal?.let { Text("Source evidence: ${it.suggestion.quote}",color=Muted) }
            actions.forEachIndexed { i,step -> TextButton(onClick={ selected=i }) { Text("Step ${i+1}: ${step.title.ifBlank { "Untitled" }}${if(i<completed) " · completed" else ""}${if(i==index) " · editing" else ""}") } }
            if(editable) {
                ScreenTabs(listOf("Note","Reminder"),if(action.kind=="note") 0 else 1) { update(WorkAction(if(it==0) "note" else "reminder",action.title)) }
                OutlinedTextField(action.title,{ if(it.length<=160) update(action.copy(title=it)) },label={ Text("Task title") })
                if(action.kind=="note") OutlinedTextField(action.body,{ if(it.length<=20_000) update(action.copy(body=it)) },label={ Text("Note content") },minLines=3,maxLines=6)
                else {
                    OutlinedButton(onClick={ activity?.let { chooseDateTime(it,action.whenMillis ?: maxOf(System.currentTimeMillis(),runAt ?: 0)) { time -> update(action.copy(whenMillis=time)) } } }) { Text(action.whenMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Choose reminder time") }
                    Text("Reminder time must be after the plan starts.",color=Muted)
                }
                Row {
                    if(index>completed) TextButton(onClick={ val copy=actions.toMutableList();java.util.Collections.swap(copy,index,index-1);actions=copy;selected=index-1 }) { Text("Move earlier") }
                    if(index<actions.lastIndex) TextButton(onClick={ val copy=actions.toMutableList();java.util.Collections.swap(copy,index,index+1);actions=copy;selected=index+1 }) { Text("Move later") }
                }
                if(actions.size>completed+1) TextButton(onClick={ actions=actions.filterIndexed { i,_ -> i!=index };selected=(index-1).coerceAtLeast(completed) }) { Text("Remove step") }
            } else Text("This step is already saved and cannot be changed here. ${action.body}",color=Muted)
            if(actions.size<WorkCoordinator.MAX_STEPS) OutlinedButton(onClick={ actions=actions+WorkAction("note","","");selected=actions.lastIndex }) { Text("Add step") }
            HorizontalDivider()
            Text(runAt?.let { "Start: ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "Start after approval")
            OutlinedButton(onClick={ activity?.let { chooseDateTime(it,runAt ?: System.currentTimeMillis()) { runAt=it } } }) { Text("Schedule start") }
            if(runAt!=null) TextButton(onClick={ runAt=null }) { Text("Run after approval instead") }
            Text("One scheduled run, not a repeating grant. Android may delay it; more than 15 minutes late needs approval again.",color=Muted)
            if(!valid) Text("Complete every remaining step and choose future times before saving.",color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={ TextButton(enabled=valid,onClick={ onSave(actions,runAt) }) { Text("Save task for review") } },dismissButton={ TextButton(onClick=onDismiss) { Text("Cancel") } })
}

internal fun workState(state: String)=when(state) {
    "waiting_for_approval" -> "Needs approval";"waiting_for_input" -> "Needs review";"queued" -> "Approved, waiting to run"
    "completed" -> "Completed";"step_completed" -> "Step verified";"cancelled" -> "Cancelled";"paused" -> "Paused";else -> state.replaceFirstChar { it.uppercase() }
}
