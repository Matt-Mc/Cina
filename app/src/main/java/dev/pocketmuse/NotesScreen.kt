package dev.pocketmuse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NotesScreen(vm: AssistantViewModel) {
    val notes by vm.notes.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Note?>(null) }
    Page("Notes", "Small things worth keeping close.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${notes.size} saved on this phone", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { editingId = null; title = ""; body = ""; editorOpen = true }) { Text("+ New note") }
        }
        if (notes.isNotEmpty()) OutlinedTextField(query, { query = it }, label = { Text("Search notes") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val filtered = notes.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        if (notes.isEmpty()) EmptyState("Keep a thought", "Write a note here, ask Cina to save one in chat, or use the Quick Note widget.")
        else if (filtered.isEmpty()) Text("No notes match your search.", color = Muted)
        filtered.forEach { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { editingId = note.id; title = note.title; body = note.body; editorOpen = true }.padding(vertical = 12.dp)) {
                    Text(note.title, fontWeight = FontWeight.Medium, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(note.body, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = note })
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
    if (editorOpen) AlertDialog(onDismissRequest = { editorOpen = false }, containerColor = Paper,
        title = { Text(if (editingId == null) "New note" else "Edit note") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
        } },
        confirmButton = { TextButton(onClick = {
            val id = editingId
            if (id == null) vm.addNote(title, body) else vm.editNote(id, title, body)
            editorOpen = false
        }, enabled = title.isNotBlank() && body.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancel") } })
    deleting?.let { note -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${note.title}?") },
        confirmButton = { TextButton(onClick = { vm.deleteNote(note.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
