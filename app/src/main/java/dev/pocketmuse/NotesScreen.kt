package dev.pocketmuse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal fun NotesScreen(vm: AssistantViewModel,openId: Long? = null,startNew: Boolean = false,onOpened: () -> Unit = {}) {
    val notes by vm.notes.collectAsState()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if(uri != null) vm.exportNotes(uri) }
    var query by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Note?>(null) }
    val prefs=LocalContext.current.getSharedPreferences("note_draft",0)
    var hasDraft by remember { mutableStateOf(prefs.getBoolean("exists",false)) }
    var discardDraft by remember { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf<Note?>(null) }
    fun persistDraft() { prefs.edit().putBoolean("exists",true).putLong("id",editingId ?: -1).putString("title",title).putString("body",body).apply();hasDraft=true }
    fun clearDraft() { prefs.edit().clear().apply();hasDraft=false }
    fun newNote() { editingId=null;title="";body="";editorOpen=true }
    fun resumeDraft() {
        editingId=prefs.getLong("id",-1).takeIf { id -> notes.any { it.id==id } }
        title=prefs.getString("title","").orEmpty();body=prefs.getString("body","").orEmpty();editorOpen=true
    }
    fun openNote(note: Note) {
        if(hasDraft && prefs.getLong("id",-1)!=note.id) pendingOpen=note
        else if(hasDraft) resumeDraft()
        else { editingId=note.id;title=note.title;body=note.body;editorOpen=true }
    }
    LaunchedEffect(openId,startNew,notes) {
        if(startNew) { if(hasDraft) { resumeDraft() } else newNote();onOpened() }
        else if(openId!=null) notes.firstOrNull { it.id==openId }?.let { openNote(it);onOpened() }
    }
    Page("Notes", "Small things worth keeping close.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${notes.size} saved on this phone", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { if(hasDraft) { resumeDraft() } else newNote() }) { Text("+ New note") }
        }
        if(hasDraft && !editorOpen) Row {
            TextButton(onClick={ resumeDraft() }) { Text("Resume draft") }
            TextButton(onClick={ discardDraft=true }) { Text("Discard draft") }
        }
        if (notes.isNotEmpty()) TextButton(onClick = { export.launch("cina-notes.md") }) { Text("Export notes") }
        if (notes.isNotEmpty()) OutlinedTextField(query, { query = it }, label = { Text("Search notes") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val filtered = notes.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
        if (notes.isEmpty()) EmptyState("Keep a thought", "Write a note here, ask Cina to save one in chat, or use the Quick Note widget.")
        else if (filtered.isEmpty()) Text("No notes match your search.", color = Muted)
        filtered.forEach { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { openNote(note) }.padding(vertical = 12.dp)) {
                    Text(note.title, fontWeight = FontWeight.Medium, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(note.body, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Options for ${note.title}" }) { Text("⋯") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = note })
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
    if(editorOpen) Dialog(onDismissRequest={ editorOpen=false },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=Paper) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(editingId==null) "New note" else "Edit note",fontSize=23.sp,color=Ink,modifier=Modifier.weight(1f))
                    TextButton(onClick={ editorOpen=false }) { Text("Close") }
                    Button(onClick={ val id=editingId;if(id==null) vm.addNote(title,body) else vm.editNote(id,title,body);clearDraft();editorOpen=false },enabled=title.isNotBlank() && body.isNotBlank()) { Text("Save") }
                }
                Text("Draft kept on this phone until you save or discard it.",color=Muted,fontSize=14.sp)
                OutlinedTextField(title,{ title=it;persistDraft() },label={ Text("Title") },modifier=Modifier.fillMaxWidth(),singleLine=true)
                OutlinedTextField(body,{ body=it;persistDraft() },label={ Text("Note") },modifier=Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if(discardDraft) AlertDialog(onDismissRequest={ discardDraft=false },title={ Text("Discard this draft?") },text={ Text("Your saved notes stay unchanged.") },confirmButton={ TextButton(onClick={ clearDraft();discardDraft=false }) { Text("Discard") } },dismissButton={ TextButton(onClick={ discardDraft=false }) { Text("Keep draft") } })
    pendingOpen?.let { note -> AlertDialog(onDismissRequest={ pendingOpen=null },title={ Text("You have an unfinished draft") },text={ Text("Resume it, or discard it before editing another note.") },
        confirmButton={ TextButton(onClick={ pendingOpen=null;resumeDraft() }) { Text("Resume draft") } },
        dismissButton={ TextButton(onClick={ clearDraft();pendingOpen=null;editingId=note.id;title=note.title;body=note.body;editorOpen=true }) { Text("Discard draft & open") } }) }
    deleting?.let { note -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${note.title}?") },
        confirmButton = { TextButton(onClick = { vm.deleteNote(note.id); deleting = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
