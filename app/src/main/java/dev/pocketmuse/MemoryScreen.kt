package dev.pocketmuse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ColumnScope.MemoryContent(vm: AssistantViewModel, onOpenChat: () -> Unit) {
    val memories by vm.memories.collectAsState(); val enabled by vm.memoryEnabled.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    val stalled by vm.stalledMemories.collectAsState()
    var editing by remember { mutableStateOf<Memory?>(null) }; var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingToggle("Memory", "Use saved details and suggest new ones from your messages.", enabled, vm::setMemoryEnabled)
        Text("Every suggested detail needs your review before it helps future chats. Cina stores facts on this phone. Temporary details expire. Turning Memory off stops collection and use; it does not delete saved details.", color = Muted, fontSize = 13.sp)
        HorizontalDivider(color = Line)
        if(stalled > 0) {
            Text("$stalled message(s) could not be checked for memory after three attempts.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = vm::retryMemoryProcessing) { Text("Retry memory processing") }
        }
        if (suggestions.isNotEmpty()) SectionTitle("Suggested memories")
        suggestions.forEach { suggestion ->
            var replacement by remember(suggestion.id) { mutableStateOf(suggestion.replacesId) }
            var chooseReplacement by remember(suggestion.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(suggestion.fact, color = Ink)
                suggestion.replacesId?.let { id -> memories.firstOrNull { it.id == id }?.let { old -> Text("Replaces: ${old.fact}", color = Muted, fontSize = 12.sp) } }
                TextButton(onClick = { vm.openChat(suggestion.sourceChatId); onOpenChat() }) { Text("Open source chat") }
                if (memories.isNotEmpty()) TextButton(onClick = { chooseReplacement = !chooseReplacement }) { Text(if (replacement == null) "Replace an existing detail…" else "Change replacement…") }
                if (chooseReplacement) {
                    TextButton(onClick = { replacement = null; chooseReplacement = false }) { Text("Keep as a separate detail") }
                    memories.forEach { old -> TextButton(onClick = { replacement = old.id; chooseReplacement = false }) { Text(old.fact) } }
                }
                replacement?.let { id -> memories.firstOrNull { it.id == id }?.let { Text("Will replace: ${it.fact}", color = Muted) } }
                Row { TextButton(onClick = { vm.approveMemory(suggestion.id, replacement) }) { Text("Remember") }; TextButton(onClick = { vm.dismissMemory(suggestion.id) }) { Text("Dismiss") } }
            }
        }
        if(memories.isEmpty()) EmptyState("Keep useful details", "Review suggested details here, or explicitly ask Cina to remember something in chat.")
        memories.forEach { memory ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(memory.fact, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                memory.expiresMillis?.let { expiry -> Text("${if(expiry <= System.currentTimeMillis()) "Expired" else "Relevant until"} · ${java.time.Instant.ofEpochMilli(expiry).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}", color = Muted, fontSize = 12.sp) }
                memory.sourceChatId?.let { sourceId ->
                    TextButton(onClick = { vm.openChat(sourceId); onOpenChat() }) { Text("Open source chat") }
                }
                Row { TextButton(onClick = { editing = memory; draft = memory.fact }) { Text("Edit") }; TextButton(onClick = { vm.deleteMemory(memory.id) }) { Text("Delete", color = Muted) } }
            }
            HorizontalDivider(color = Line)
        }
    }
    if(editing != null) AlertDialog(onDismissRequest = { editing = null }, containerColor = Paper, title = { Text("Edit memory") },
        text = { OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { vm.editMemory(editing!!.id, draft); editing = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
}

