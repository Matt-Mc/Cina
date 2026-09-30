package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@Composable
internal fun SkillsScreen(vm: AssistantViewModel) {
    val skills by vm.skills.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    Page("Skills", "Guide how Cina handles familiar tasks.") {
        Text("Enabled skills guide matching requests. Changes apply to the next response. Skills use the tools and permissions you already allow.", color = Muted, fontSize = 13.sp)
        if (busy) Text("Wait for the current response to finish before changing skills.", color = Muted, fontSize = 12.sp)
        skills.forEach { skill ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingToggle(skill.title, skill.description, skill.enabled) { if (!busy) vm.setSkillEnabled(skill.id, it) }
                Text(skill.instructions, color = Muted, fontSize = 13.sp)
                TextButton(enabled = !busy, onClick = { editingId = skill.id; draft = skill.instructions }) { Text("Edit instructions") }
                HorizontalDivider(color = Line)
            }
        }
    }
    val editing = skills.firstOrNull { it.id == editingId }
    if (editing != null) AlertDialog(
        onDismissRequest = { editingId = null },
        title = { Text(editing.title) },
        text = { Column {
            OutlinedTextField(draft, { draft = it }, label = { Text("Instructions") },
                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                isError = draft.trim().isEmpty() || draft.length > AssistantSkills.MAX_INSTRUCTIONS)
            Text("${draft.length}/${AssistantSkills.MAX_INSTRUCTIONS} characters", color = Muted, fontSize = 12.sp)
            TextButton(enabled = !busy, onClick = { vm.resetSkill(editing.id); editingId = null }) { Text("Restore default instructions") }
        } },
        confirmButton = { TextButton(enabled = !busy && draft.trim().isNotEmpty() && draft.length <= AssistantSkills.MAX_INSTRUCTIONS,
            onClick = { vm.saveSkillInstructions(editing.id, draft); editingId = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } }
    )
}
