package dev.pocketmuse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ContextLibraryScreen(vm: ContextViewModel,onBack: () -> Unit,onOpenSource: (String) -> Unit, backLabel: String = "Back to Inbox", selectedId: String? = null, selectionKind: String? = null) {
    var storage by rememberSaveable { mutableStateOf(false) }
    if(storage) { LibraryStorageScreen(onBack={ storage=false;vm.refresh();vm.clearResults() });return }
    val projects by vm.projects.collectAsState()
    val knowledge by vm.knowledge.collectAsState()
    val hits by vm.hits.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val history by vm.history.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyKnowledge by rememberSaveable { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var editKnowledge by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var deleteKnowledge by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var historyEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var projectEditor by remember { mutableStateOf(false) }
    var projectToEdit by remember { mutableStateOf<ContextProject?>(null) }
    var projectToDelete by remember { mutableStateOf<ContextProject?>(null) }
    LaunchedEffect(selectedId,selectionKind) { if(selectedId!=null) tab=if(selectionKind=="project") 2 else 1 }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) { vm.refresh() } }
        lifecycle.addObserver(observer);vm.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { onBack() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=20.dp)) {
        item { TextButton(onClick=onBack) { Text(backLabel) }; Text("Knowledge & projects",style=MaterialTheme.typography.headlineMedium,color=Ink) }
        item { TextButton(onClick={ storage=true }) { Text("Storage & backup") } }
        item { ScreenTabs(listOf("Find","Knowledge","Projects"),tab) { tab=it } }
        if(message.isNotBlank()) item { Text(message,color=Muted) }
        when(tab) {
            0 -> {
                item { Text("Search by keywords; all words must occur in a match. Original passages can contain older or conflicting claims; current knowledge appears first.",color=Muted) }
                item { OutlinedTextField(query,{ query=it;vm.clearResults() },label={ Text("Search saved context") },modifier=Modifier.fillMaxWidth(),singleLine=true) }
                item {
                    Box {
                        TextButton(onClick={ projectMenu=true }) { Text("Project: ${projects.firstOrNull { it.id==projectFilter }?.name ?: "All projects"}") }
                        DropdownMenu(projectMenu,{ projectMenu=false }) {
                            DropdownMenuItem(text={ Text("All projects") },onClick={ projectFilter=null;projectMenu=false;vm.clearResults() })
                            projects.forEach { project -> DropdownMenuItem(text={ Text(project.name) },onClick={ projectFilter=project.id;projectMenu=false;vm.clearResults() }) }
                        }
                    }
                    Row { Checkbox(onlyKnowledge,{ onlyKnowledge=it;vm.clearResults() },modifier=Modifier.semantics { contentDescription="Search current knowledge only" });Text("Current knowledge only",modifier=Modifier.padding(top=12.dp)) }
                    Button(onClick={ vm.search(query,projectFilter?.takeIf { id -> projects.any { it.id==id } },onlyKnowledge) },enabled=query.isNotBlank() && !busy) { Text("Find matches") }
                }
                items(hits,key={ "${it.kind}:${it.recordId}" }) { hit ->
                    Surface(color=Soft,shape=MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        SectionTitle(hit.title)
                        Text(when(hit.kind) { "knowledge" -> "Current reviewed knowledge"; "caption" -> "Original shared caption"; "title" -> "Source title"; else -> "Original source passage · characters ${hit.start+1}–${hit.end}" },color=Muted)
                        Text(hit.body.take(1200).ifBlank { "Original document saved; no text preview." })
                        if(hit.body.length>1200) Text("Excerpt shown; open the source for the rest.",color=Muted)
                        TextButton(onClick={ onOpenSource(hit.sourceId) }) { Text("Open source") }
                    } }
                }
            }
            1 -> {
                item { Text("Facts you explicitly saved from reviewed suggestions. Corrections keep a history. This library is not automatically added to chat prompts.",color=Muted) }
                if(knowledge.isEmpty()) item { EmptyState("No saved knowledge yet","Review a fact suggestion in the Inbox, then choose Save fact.") }
                items(knowledge.filter { selectedId==null || selectionKind!="knowledge" || it.id==selectedId },key={ it.id }) { entry ->
                    Surface(color=Soft,shape=MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(entry.text,color=Ink)
                        Text(knowledgeState(entry),color=Muted)
                        Text("Original evidence: ${entry.quote}",color=Muted)
                        Text("Corrections reflect your review; the original quote is retained for comparison.",color=Muted)
                        Row {
                            TextButton(onClick={ editKnowledge=entry },enabled=!busy) { Text("Correct / review") }
                            TextButton(onClick={ historyEntry=entry;vm.history(entry.id) }) { Text("History") }
                        }
                        Row {
                            TextButton(onClick={ onOpenSource(entry.sourceId) }) { Text("Open source") }
                            TextButton(onClick={ deleteKnowledge=entry },enabled=!busy) { Text("Delete fact") }
                        }
                    } }
                }
            }
            2 -> {
                item { Text("Link a source to one or more projects from its Inbox detail. Removing a project keeps its sources and knowledge.",color=Muted) }
                item { Button(onClick={ projectToEdit=null;projectEditor=true },enabled=!busy) { Text("New project") } }
                items(projects.filter { selectedId==null || selectionKind!="project" || it.id==selectedId },key={ it.id }) { project ->
                    Column { SectionTitle(project.name)
                        Row {
                            TextButton(onClick={ projectFilter=project.id;tab=0;vm.clearResults() }) { Text("Search project") }
                            TextButton(onClick={ projectToEdit=project;projectEditor=true },enabled=!busy) { Text("Rename") }
                            TextButton(onClick={ projectToDelete=project },enabled=!busy) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
    editKnowledge?.let { entry -> KnowledgeEditor(entry,{ editKnowledge=null }) { text,status,expires,reason -> vm.revise(entry,text,status,expires,reason);editKnowledge=null } }
    deleteKnowledge?.let { entry -> AlertDialog(onDismissRequest={ deleteKnowledge=null },title={ Text("Delete this knowledge?") },text={ Text("The fact and its revision history will be removed. The original source and reviewed suggestion stay in your Inbox.") },
        confirmButton={ TextButton(onClick={ vm.deleteKnowledge(entry.id);deleteKnowledge=null }) { Text("Delete") } },dismissButton={ TextButton(onClick={ deleteKnowledge=null }) { Text("Keep") } }) }
    historyEntry?.let { entry -> AlertDialog(onDismissRequest={ historyEntry=null },title={ Text("Knowledge history") },text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            history.forEach { revision -> Text("Revision ${revision.revision} · ${revision.status}\n${revision.text}\n${revision.reason}\n${DateFormat.getDateTimeInstance().format(Date(revision.changedAt))}") }
            if(history.isEmpty()) Text("No history loaded.")
        }
    },confirmButton={ TextButton(onClick={ historyEntry=null }) { Text("Done") } }) }
    if(projectEditor) {
        var name by rememberSaveable(projectToEdit?.id) { mutableStateOf(projectToEdit?.name.orEmpty()) }
        AlertDialog(onDismissRequest={ projectEditor=false },title={ Text(if(projectToEdit==null) "New project" else "Rename project") },text={ OutlinedTextField(name,{ name=it },label={ Text("Project name") }) },
            confirmButton={ TextButton(enabled=name.trim().length in 1..80,onClick={ vm.saveProject(name,projectToEdit);projectEditor=false }) { Text("Save project") } },dismissButton={ TextButton(onClick={ projectEditor=false }) { Text("Cancel") } })
    }
    projectToDelete?.let { project -> AlertDialog(onDismissRequest={ projectToDelete=null },title={ Text("Remove ${project.name}?") },text={ Text("Project membership and name history will be removed. Sources and knowledge will remain.") },
        confirmButton={ TextButton(onClick={ vm.deleteProject(project.id);projectToDelete=null }) { Text("Remove project") } },dismissButton={ TextButton(onClick={ projectToDelete=null }) { Text("Cancel") } }) }
}

internal fun knowledgeState(entry: KnowledgeEntry): String {
    val state=when { entry.status=="archived" -> "Archived"; entry.status=="needs_review" -> "Needs review"; entry.expiresAt!=null && entry.expiresAt<=System.currentTimeMillis() -> "Expired"; else -> "Current" }
    return "$state · revision ${entry.revision}" + (entry.expiresAt?.let { " · valid until ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: " · no expiry")
}

@Composable
internal fun KnowledgePromotionDialog(proposal: IntakeProposal,onDismiss: () -> Unit,onSave: (Long?) -> Unit) {
    var expiry by rememberSaveable { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("Save as knowledge?") },text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(proposal.suggestion.text)
        Text("This saves a separate, source-linked fact in your local knowledge library. Check the wording and validity first.")
        ValidityChoice(expiry,{ expiry=it },false)
    } },confirmButton={ TextButton(onClick={ onSave(validUntil(expiry,null)) }) { Text("Save knowledge") } },dismissButton={ TextButton(onClick=onDismiss) { Text("Cancel") } })
}

@Composable
private fun KnowledgeEditor(entry: KnowledgeEntry,onDismiss: () -> Unit,onSave: (String,String,Long?,String) -> Unit) {
    var text by rememberSaveable(entry.id,entry.revision) { mutableStateOf(entry.text) }
    var reason by rememberSaveable(entry.id,entry.revision) { mutableStateOf("") }
    var status by rememberSaveable(entry.id,entry.revision) { mutableIntStateOf(when(entry.status) { "needs_review" -> 1; "archived" -> 2; else -> 0 }) }
    var validity by rememberSaveable(entry.id,entry.revision) { mutableIntStateOf(3) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("Correct or review knowledge") },text={ Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(text,{ text=it },label={ Text("Fact") },minLines=2,maxLines=5)
        OutlinedTextField(reason,{ reason=it },label={ Text("Reason for this revision") },maxLines=3)
        ScreenTabs(listOf("Current","Review","Archive"),status) { status=it }
        Text("Use Review for unresolved conflicts; these facts are excluded from current search.")
        ValidityChoice(validity,{ validity=it },true)
        Text("Original evidence: ${entry.quote}",color=Muted)
    } },confirmButton={ TextButton(enabled=text.trim().length in 1..600 && reason.trim().length in 1..200,onClick={ onSave(text,listOf("active","needs_review","archived")[status],validUntil(validity,entry.expiresAt),reason) }) { Text("Save revision") } },dismissButton={ TextButton(onClick=onDismiss) { Text("Cancel") } })
}

@Composable
private fun ValidityChoice(value: Int,onChange: (Int) -> Unit,allowKeep: Boolean) {
    val choices=listOf("No expiry","Valid for 7 days","Valid for 30 days") + if(allowKeep) listOf("Keep current validity") else emptyList()
    choices.forEachIndexed { index,label -> Row { RadioButton(value==index,{ onChange(index) },modifier=Modifier.semantics { contentDescription=label });Text(label,modifier=Modifier.padding(top=12.dp)) } }
}
private fun validUntil(value: Int,old: Long?): Long? = when(value) { 1 -> System.currentTimeMillis()+7L*86400000;2 -> System.currentTimeMillis()+30L*86400000;3 -> old;else -> null }
