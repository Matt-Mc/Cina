package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun WorkOverview(vm: AssistantViewModel,onTab: (Int)->Unit,work: DurableWorkViewModel=viewModel()) {
    val actions by work.tasks.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val schedules by vm.scheduledTasks.collectAsState()
    val goals by vm.agentTasks.collectAsState()
    val homeJobs by vm.homeJobs.collectAsState()
    LaunchedEffect(Unit) { while(true) { work.refresh();delay(1500) } }
    val attention=actions.filter { it.state in setOf("waiting_for_approval","waiting_for_input") }
    val queued=actions.filter { it.state=="queued" }.sortedBy { it.runAt ?: 0 }
    Text("Needs your attention",color=Ink,fontWeight=FontWeight.Medium)
    if(attention.isEmpty() && goals.none { it.status=="waiting" } && schedules.none { !it.lastError.isNullOrBlank() } && homeJobs.none { it.state in setOf("failed","interrupted") || it.error.isNotBlank() })
        Text("Nothing is waiting for you.",color=Muted)
    attention.take(12).forEach { task -> OverviewRow(task.action.title,
        if(task.state=="waiting_for_approval") "Review ${task.steps.count { it.state!="completed" }} remaining steps" else task.message,"Review actions") { onTab(3) } }
    goals.filter { it.status=="waiting" }.take(4).forEach { OverviewRow(it.goal,"Chat goal needs your input","Open goals") { onTab(0) } }
    schedules.filter { !it.lastError.isNullOrBlank() }.take(4).forEach { OverviewRow(it.title,"Scheduled prompt needs attention","Open scheduled") { onTab(2) } }
    HomeWorkRows(vm, attention = true)
    Text("Coming up",color=Ink,fontWeight=FontWeight.Medium)
    queued.take(8).forEach { task -> OverviewRow(task.action.title,
        "${task.steps.count { it.state=="completed" }}/${task.steps.size} verified · " +
            (task.runAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Ready to run"),"Open actions") { onTab(3) } }
    reminders.filter { !it.done }.sortedBy { it.whenMillis }.take(5).forEach {
        OverviewRow(it.title,"Reminder · ${DateFormat.getDateTimeInstance().format(Date(it.whenMillis))}","Open reminders") { onTab(1) } }
    schedules.filter { it.enabled }.sortedBy { it.nextRunMillis }.take(5).forEach {
        OverviewRow(it.title,"Prompt · ${DateFormat.getDateTimeInstance().format(Date(it.nextRunMillis))}","Open scheduled") { onTab(2) } }
    Text("Progress and results",color=Ink,fontWeight=FontWeight.Medium)
    HomeWorkRows(vm)
    actions.filter { it.state in setOf("completed","paused","cancelled") }.take(8).forEach {
        OverviewRow(it.action.title,"${it.state.replaceFirstChar(Char::uppercase)} · ${it.steps.count { step -> step.state=="completed" }}/${it.steps.size} steps verified","Open actions") { onTab(3) } }
    goals.filter { it.status!="waiting" }.take(4).forEach { OverviewRow(it.goal,"Chat goal · ${it.status}","Open goals") { onTab(0) } }
    schedules.filter { it.lastRunMillis!=null && !it.lastResult.isNullOrBlank() }.sortedByDescending { it.lastRunMillis }.take(4).forEach {
        OverviewRow(it.title,"Prompt result · ${it.lastResult.orEmpty().take(200)}","Open scheduled") { onTab(2) } }
    Text("Background work may be delayed by Android. Approvals and results stay here even when notifications are off. Open a category to see all items.",color=Muted)
}

@Composable
private fun OverviewRow(title: String,detail: String,link: String,open: ()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(title,color=Ink,fontWeight=FontWeight.Medium)
        Text(detail,color=Muted)
        TextButton(onClick=open) { Text(link) }
    } }
}
