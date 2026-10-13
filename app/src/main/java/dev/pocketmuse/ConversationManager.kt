package dev.pocketmuse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationSheet(vm: AssistantViewModel, onDismiss: () -> Unit) {
    val chats by vm.chats.collectAsState()
    val active by vm.active.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteMemories by rememberSaveable { mutableStateOf(false) }
    var exportingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> exportingId?.let { id -> if (uri != null) vm.exportChat(id, uri) } }
    val filtered = remember(chats, query, archived) { chats.filter { it.archived == archived && (query.isBlank() || it.title.contains(query, true) || vm.chatMatches(it.id, query)) } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Conversations")
            OutlinedTextField(query, { query = it }, label = { Text("Search chats & messages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ScreenTabs(listOf("Chats", "Archived"), if (archived) 1 else 0) { archived = it == 1 }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                if (filtered.isEmpty()) item { Text("No conversations here.", Modifier.padding(vertical = 16.dp), color = Muted) }
                items(filtered, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { vm.openChat(item.id); onDismiss() }, modifier = Modifier.weight(1f)) { Text("${item.title}${if (item.id == active) " · Current" else ""}") }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }) { Text("Manage") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { editingId = item.id; title = item.title; menu = false })
                                DropdownMenuItem(text = { Text(if (item.archived) "Unarchive" else "Archive") }, onClick = { vm.archiveChat(item.id, !item.archived); menu = false })
                                DropdownMenuItem(text = { Text("Export chat") }, onClick = { exportingId = item.id; export.launch("cina-chat-${item.id}.md"); menu = false })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { deletingId = item.id; deleteMemories = false; menu = false })
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
            TextButton(onClick = { vm.newChat(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
        }
    }
    editingId?.let { id -> AlertDialog(onDismissRequest = { editingId = null }, title = { Text("Rename chat") },
        text = { OutlinedTextField(title, { title = it.take(120) }, label = { Text("Chat name") }) },
        confirmButton = { TextButton(onClick = { vm.renameChat(id, title); editingId = null }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } }) }
    deletingId?.let { id -> AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Delete this chat?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This removes its messages, attached text, summaries, goal history and pending memory suggestions. Notes and reminders stay saved.")
            SettingToggle("Also delete memories from this chat", "Off keeps saved memories without a link to this chat.", deleteMemories) { deleteMemories = it }
        } },
        confirmButton = { TextButton(onClick = { vm.deleteChat(id, deleteMemories); deletingId = null }) { Text("Delete chat") } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } }) }
}

@Composable
internal fun AttachmentsDialog(vm: AssistantViewModel, onDismiss: () -> Unit) {
    val attachments by vm.attachments.collectAsState()
    var reading by remember { mutableStateOf<ChatAttachment?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(reading?.name ?: "Attached files") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            if (reading != null) {
                TextButton(onClick = { reading = null }) { Text("Back to files") }
                Text(remember(reading?.id) { vm.attachmentText(reading!!.id) }, color = Ink)
            } else {
                Text("Cina uses extracted text from these files. Removing one deletes that text and the chat summary; previous messages may still quote it.", color = Muted)
                attachments.forEach { file ->
                    Text(file.name, Modifier.padding(top = 16.dp), color = Ink)
                    Row { TextButton(onClick = { reading = file }) { Text("Read extracted text") }; TextButton(onClick = { vm.removeAttachment(file.id) }) { Text("Remove") } }
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
