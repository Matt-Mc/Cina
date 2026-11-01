package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

internal data class UpcomingWork(val kind: String,val id: String,val title: String,val time: Long,val detail: String)
internal fun orderedUpcoming(items: List<UpcomingWork>) = items.sortedWith(compareBy<UpcomingWork> { it.time }.thenBy { it.kind }.thenBy { it.id })

@Composable
internal fun WorkOverview(vm: AssistantViewModel,onOpen: (String,String)->Unit,work: DurableWorkViewModel=viewModel()) {
    val actions by work.tasks.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val schedules by vm.scheduledTasks.collectAsState()
    LaunchedEffect(Unit) { while(true) { work.refresh();delay(1500) } }
    val attention=actions.filter { it.state in setOf("waiting_for_approval","waiting_for_input") }
    val failedSchedules=schedules.filter { !it.lastError.isNullOrBlank() }
    SectionTitle("Needs you · ${attention.size+failedSchedules.size}")
    if(attention.isEmpty() && failedSchedules.isEmpty()) Text("No items need attention.",color=Muted)
    attention.forEach { task -> NavigationRow(task.action.title,if(task.state=="waiting_for_approval") "Review ${task.steps.count { it.state!="completed" }} remaining steps" else task.message) { onOpen("action",task.id) } }
    failedSchedules.forEach { NavigationRow(it.title,"Review failed scheduled prompt") { onOpen("schedule",it.id.toString()) } }
    val upcoming=orderedUpcoming(buildList {
        actions.filter { it.state=="queued" }.forEach { add(UpcomingWork("action",it.id,it.action.title,it.runAt ?: 0,"Approved · on this phone")) }
        reminders.filter { !it.done }.forEach { add(UpcomingWork("reminder",it.id.toString(),it.title,it.whenMillis,"Reminder")) }
        schedules.filter { it.enabled }.forEach { add(UpcomingWork("schedule",it.id.toString(),it.title,it.nextRunMillis,"Scheduled on this phone")) }
    })
    val now=System.currentTimeMillis()
    val overdue=upcoming.filter { it.time>0 && it.time<now }
    if(overdue.isNotEmpty()) {
        SectionTitle("Due now · ${overdue.size}")
        overdue.forEach { row -> NavigationRow(row.title,"${row.detail} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(row.time))}") { onOpen(row.kind,row.id) } }
    }
    SectionTitle("Upcoming")
    val future=upcoming.filter { it.time==0L || it.time>=now }
    if(future.isEmpty()) Text("Nothing scheduled. Add work when you need it.",color=Muted)
    future.forEach { row -> NavigationRow(row.title,"${row.detail} · ${if(row.time==0L) "Ready to run" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(row.time))}") { onOpen(row.kind,row.id) } }
    SectionTitle("Recent results")
    val finished=actions.filter { it.state in setOf("completed","paused","cancelled") }.take(8)
    finished.forEach { NavigationRow(it.action.title,"${workState(it.state)} · view details and results") { onOpen("action",it.id) } }
    schedules.filter { it.lastRunMillis!=null && !it.lastResult.isNullOrBlank() }.sortedByDescending { it.lastRunMillis }.take(8).forEach { NavigationRow(it.title,"View scheduled result") { onOpen("schedule",it.id.toString()) } }
    Text("Scheduled phone work can be delayed by Android. Approvals and results stay here.",color=Muted,style=MaterialTheme.typography.bodyMedium)
}
