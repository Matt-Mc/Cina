package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun IntakeReviewCard(proposal: IntakeProposal, busy: Boolean, onOutcome: (() -> Unit)? = null,
    onUpdate: (IntakeProposal, String, String) -> Unit) {
    var editing by rememberSaveable(proposal.id) { mutableStateOf(false) }
    var text by rememberSaveable(proposal.id, proposal.revision) { mutableStateOf(proposal.suggestion.text) }
    var evidence by remember { mutableStateOf(false) }
    val label = when(proposal.suggestion.kind) {
        "summary" -> "Summary"; "filing" -> "Suggested category"; "fact" -> "Candidate fact"
        "action" -> "Possible action"; else -> "Question to resolve"
    }
    val state = when(proposal.state) { "accepted" -> "Kept"; "deferred" -> "Deferred"; "discarded" -> "Discarded"; else -> "Needs review" }
    Surface(color = Soft, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("$label · $state")
            Text(proposal.suggestion.text, color = Ink)
            if (proposal.edited) Text("Edited by you. Check that the source still supports this wording.", color = Muted)
            TextButton(onClick = { evidence = true }) { Text("View source quote") }
            if(onOutcome!=null) Button(onClick=onOutcome,enabled=!busy) { Text(if(proposal.suggestion.kind=="fact") "Save fact…" else "Create task…") }
            else if(proposal.state!="accepted") Button(onClick={ onUpdate(proposal,proposal.suggestion.text,"accepted") },enabled=!busy) { Text(if(proposal.suggestion.kind=="filing") "Use category" else "Keep review only") }
            Row {
                TextButton(onClick={ editing=true },enabled=!busy) { Text("Edit") }
                TextButton(onClick={ onUpdate(proposal,proposal.suggestion.text,"deferred") },enabled=!busy && proposal.state!="deferred") { Text("Later") }
                var more by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick={ more=true }) { Text("More") }
                    DropdownMenu(more,{ more=false }) {
                        if(onOutcome!=null) DropdownMenuItem(text={ Text("Keep review only") },enabled=!busy && proposal.state!="accepted",onClick={ more=false;onUpdate(proposal,proposal.suggestion.text,"accepted") })
                        DropdownMenuItem(text={ Text("Discard suggestion") },enabled=!busy && proposal.state!="discarded",onClick={ more=false;onUpdate(proposal,proposal.suggestion.text,"discarded") })
                    }
                }
            }

        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("Edit $label") },
        text = { Column {
            OutlinedTextField(text, onValueChange = { text = it }, minLines = 3, maxLines = 8, label = { Text("Suggestion") })
            Text("${text.length}/600 · Saving puts this back into review.")
        } },
        confirmButton = { TextButton(enabled = !busy && text.trim().length in 1..600, onClick = {
            onUpdate(proposal, text, "pending"); editing = false
        }) { Text("Save edit") } }, dismissButton = { TextButton(onClick = { text = proposal.suggestion.text; editing = false }) { Text("Cancel") } })
    if (evidence) AlertDialog(onDismissRequest = { evidence = false }, title = { Text("Source evidence") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${if (proposal.suggestion.field == "text") "Captured content" else "Shared caption"}, characters ${proposal.suggestion.start + 1}–${proposal.suggestion.end}")
            SelectionContainer { Text(proposal.suggestion.quote) }
            Text("An exact quote confirms where this came from; it does not guarantee the suggestion is correct.")
        } }, confirmButton = { TextButton(onClick = { evidence = false }) { Text("Done") } })
}
