package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun LibraryScreen(vm: AssistantViewModel, onOpen: (String,String) -> Unit = {_,_->}, onNavigate: (String) -> Unit) {
    val library: LibraryViewModel = viewModel()
    val items by library.items.collectAsState()
    val loading by library.loading.collectAsState()
    val error by library.error.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    LaunchedEffect(query) { library.load(query) }
    val kinds = mapOf("Sources" to "source", "Notes" to "note", "Facts" to "knowledge", "Projects" to "project", "Personal details" to "memory")
    val shown = items.filter { filter == "All" || it.kind == kinds[filter] }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max=840.dp).fillMaxSize().padding(horizontal=22.dp).semantics { contentDescription="Saved library items" }, verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(vertical=20.dp)) {
        item { Text("Library",fontFamily=FontFamily.Serif,fontSize=31.sp,color=Ink) }
        item { OutlinedTextField(query,{ query=it },label={ Text("Search your library") },modifier=Modifier.fillMaxWidth(),singleLine=true) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(BuildConfig.INBOX_ENABLED) Button(onClick={ onNavigate("Inbox") }) { Text("Save something") }
            OutlinedButton(onClick={ onOpen("note","new") }) { Text("New note") }
        } }
        if(suggestions.isNotEmpty()) item { NavigationRow("${suggestions.size} personal details to review","Choose what helps future conversations") { onNavigate("Memory") } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("All") + kinds.keys.filter { BuildConfig.INBOX_ENABLED || it in listOf("Notes","Personal details") }).forEach { label ->
                FilterChip(selected=filter==label,onClick={ filter=label },label={ Text(label) })
            }
        } }
        if(loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(),color=Accent) }
        if(error.isNotBlank()) item { Text(error,color=MaterialTheme.colorScheme.error); TextButton(onClick={ library.load(query) }) { Text("Try again") } }
        if(!loading && error.isBlank() && shown.isEmpty()) item { EmptyState(if(query.isBlank()) "Keep something useful" else "No matches",if(query.isBlank()) "Save a thought, write a note, or share a document with Cina." else "Try fewer words or choose another filter.") }
        items(shown,key={ "${it.kind}:${it.id}" }) { item ->
            NavigationRow(item.title,item.label + if(query.isNotBlank() && item.body.isNotBlank()) "\n${item.body.take(180)}" else "") { onOpen(item.kind,if(item.kind=="source") item.sourceId ?: item.id else item.id) }
        }
        item { HorizontalDivider(color=Line); Text("Browse",color=Muted,fontSize=14.sp) }
        if(BuildConfig.INBOX_ENABLED) item { TextButton(onClick={ onNavigate("Inbox") }) { Text("Inbox") }; TextButton(onClick={ onNavigate("Knowledge") }) { Text("Knowledge & projects") } }
        item { TextButton(onClick={ onNavigate("Notes") }) { Text("Manage notes") }; TextButton(onClick={ onNavigate("Memory") }) { Text("Memory") } }
    }
    }
}
