package dev.pocketmuse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

@Composable
internal fun InboxScreen(vm: InboxViewModel = viewModel()) {
    val contextVm: ContextViewModel = viewModel()
    val workVm: DurableWorkViewModel = viewModel()
    val workMessage by workVm.message.collectAsState()
    val workBusy by workVm.busy.collectAsState()
    var taskProposal by remember { mutableStateOf<IntakeProposal?>(null) }
    val projects by contextVm.projects.collectAsState()
    val links by contextVm.links.collectAsState()
    val knowledge by contextVm.knowledge.collectAsState()
    val contextMessage by contextVm.message.collectAsState()
    val contextBusy by contextVm.busy.collectAsState()
    var library by rememberSaveable { mutableStateOf(false) }
    var promote by remember { mutableStateOf<IntakeProposal?>(null) }
    val entries by vm.items.collectAsState()
    val detail by vm.detail.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val review by vm.review.collectAsState()
    val interpreting by vm.interpreting.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycle.addObserver(observer); vm.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(library,detail?.item?.id,review?.revision) { if(!library) contextVm.refresh(detail?.item?.id) }
    if(library) {
        ContextLibraryScreen(contextVm,{ vm.closeDetail();vm.refresh();library=false }) { id -> vm.refresh();vm.openSource(id);library=false }
        return
    }
    promote?.let { proposal -> KnowledgePromotionDialog(proposal,{ promote=null }) { expires -> contextVm.promote(proposal,expires);promote=null } }
    taskProposal?.let { proposal -> WorkTaskEditor(null,proposal,{ taskProposal=null }) { actions,runAt -> workVm.savePlan(actions,runAt,proposal=proposal);taskProposal=null } }
    BackHandler(detail != null) { vm.closeDetail() }
    val opened = detail
    if (opened != null) {
        var previewPage by rememberSaveable(opened.item.id) { mutableIntStateOf(0) }
        val pageCount = maxOf(1, (opened.text.length + 11_999) / 12_000)
        Page(opened.item.title, "Saved ${DateFormat.getDateTimeInstance().format(Date(opened.item.capturedAt))} · ${opened.origin}") {
            TextButton(onClick = vm::closeDetail) { Text("Back to Inbox") }
            TextButton(onClick = { library=true }) { Text("Knowledge & projects") }
            if(contextMessage.isNotBlank()) Text(contextMessage,color=Muted)
            SectionTitle("Projects")
            if(projects.isEmpty()) Text("Create a project in Knowledge & projects to group sources.",color=Muted)
            projects.forEach { project -> Row {
                Checkbox(project.id in links,{ checked -> contextVm.link(opened.item.id,project.id,checked) },enabled=!contextBusy,modifier=Modifier.semantics { contentDescription="Link to ${project.name}" })
                Text(project.name,modifier=Modifier.padding(top=12.dp))
            } }
            Text(extractionDescription(opened.item.extraction), color = Muted)
            if (opened.item.duplicate) Text("Another saved item has the same original content.", color = Muted)
            if (opened.item.category.isNotBlank()) Text("Category: ${opened.item.category}", color = Accent)
            SectionTitle("Review suggestions")
            Text("Check each suggestion against its source. Keeping saves your review. Save a kept fact as knowledge, or turn a kept action into a task for separate approval.", color = Muted)
            if(workMessage.isNotBlank()) Text(workMessage,color=Muted)
            val currentReview = review
            if (currentReview?.error?.isNotBlank() == true) Text(currentReview.error, color = MaterialTheme.colorScheme.error)
            if (currentReview != null && currentReview.proposals.isEmpty()) {
                if (currentReview.state == "reviewed") Text("The model returned no supported suggestions. You can try again.", color = Muted)
                Button(onClick = vm::interpret, enabled = !busy && !interpreting && (opened.text.isNotBlank() || opened.accompanyingText.isNotBlank())) {
                    Text(if (interpreting) "Interpreting…" else "Interpret with local model")
                }
            }
            if (interpreting) TextButton(onClick = vm::cancelInterpretation) { Text("Stop interpretation") }
            currentReview?.proposals?.firstOrNull()?.let { Text("${it.coverage}\nModel: ${it.model}", color = Muted) }
            currentReview?.proposals?.forEach { proposal ->
                IntakeReviewCard(proposal, busy, vm::updateProposal)
                if(proposal.suggestion.kind=="action" && proposal.state=="accepted") TextButton(enabled=!workBusy && !busy,onClick={ taskProposal=proposal }) { Text("Create task for review") }
                if(proposal.suggestion.kind=="fact" && proposal.state=="accepted") {
                    val saved=knowledge.firstOrNull { it.proposalId==proposal.id }
                    if(saved==null) TextButton(onClick={ promote=proposal },enabled=!contextBusy && !busy) { Text("Save as knowledge") }
                    else TextButton(onClick={ library=true }) { Text("Saved knowledge · ${knowledgeState(saved)}") }
                }
            }
            if (opened.accompanyingText.isNotBlank()) {
                SectionTitle("Shared with this document")
                SelectionContainer { Text(opened.accompanyingText) }
            }
            SectionTitle("Captured content")
            SelectionContainer { Text(opened.text.drop(previewPage * 12_000).take(12_000).ifBlank { "The original document is saved. No text preview is available." }) }
            if (pageCount > 1) {
                Text("Preview ${previewPage + 1} of $pageCount", color = Muted)
                Row {
                    TextButton(onClick = { previewPage-- }, enabled = previewPage > 0) { Text("Previous") }
                    TextButton(onClick = { previewPage++ }, enabled = previewPage < pageCount - 1) { Text("Next") }
                }
            }
            TextButton(onClick = { deleting = true }, enabled = !busy) { Text("Delete captured item") }
            if (message.isNotBlank()) Text(message, color = Muted)
        }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete this item?") },
            text = { Text("This removes the source, suggestions and linked knowledge. Pending tasks are cancelled; task details and any completed notes or reminders remain in Tasks.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.delete(opened.item.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep") } })
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 20.dp)) {
            item { Text("Inbox", style = MaterialTheme.typography.headlineLarge, color = Ink) }
            item { TextButton(onClick={ library=true }) { Text("Knowledge & projects") } }
            item { Text("Save text, links, and documents for later. Everything stays on this phone. Links are saved without opening them.", color = Muted) }
            item { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Something to keep") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, enabled = !busy) }
            item { Button(onClick = { vm.captureText(draft) { draft = "" } }, enabled = !busy && draft.isNotBlank()) { Text(if (busy) "Saving…" else "Save to Inbox") } }
            if (message.isNotBlank()) item { Text(message, color = Muted) }
            if (entries.isEmpty()) item { EmptyState("Start with anything worth keeping", "Write above, or use Share → Cina Inbox from another app. Supports text and PDFs up to 8 MB.") }
            items(entries, key = { it.id }) { entry ->
                val state = when(entry.state) { "needs_review" -> "Needs review"; "reviewed" -> "Reviewed"; else -> "Captured" }
                PaperRow(entry.title, "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.capturedAt))} · $state${if (entry.category.isNotBlank()) " · ${entry.category}" else ""}${if (entry.duplicate) " · Duplicate content" else ""}", "Open") { vm.open(entry) }
            }
        }
    }
}

internal fun extractionDescription(status: String): String = when (status) {
    "needs_text" -> "Original saved. This PDF has no selectable text; OCR is not available yet."
    "preview_limited" -> "Original saved in full. Preview is limited to 100 pages or 250,000 characters."
    "extraction_failed" -> "Original saved. Text extraction failed; this PDF may be damaged or password protected."
    else -> "Original and text saved on this phone."
}
