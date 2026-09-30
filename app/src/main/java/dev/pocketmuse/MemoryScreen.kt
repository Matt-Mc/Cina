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
    var editing by remember { mutableStateOf<Memory?>(null) }; var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Remember useful details", Modifier.weight(1f), color = Ink); Switch(enabled, onCheckedChange = vm::setMemoryEnabled) }
        HorizontalDivider(color = Line)
        if (suggestions.isNotEmpty()) SectionTitle("Suggested memories")
        suggestions.forEach { suggestion ->
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(suggestion.fact, color = Ink)
                TextButton(onClick = { vm.openChat(suggestion.sourceChatId); onOpenChat() }) { Text("Open source chat") }
                Row { TextButton(onClick = { vm.approveMemory(suggestion.id) }) { Text("Remember") }; TextButton(onClick = { vm.dismissMemory(suggestion.id) }) { Text("Dismiss") } }
            }
        }
        if(memories.isEmpty()) EmptyState("You decide what stays", "Cina suggests useful personal details from chat. Review each suggestion before it becomes a saved memory.")
        memories.forEach { memory ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(memory.fact, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
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

