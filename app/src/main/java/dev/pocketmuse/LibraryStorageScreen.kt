package dev.pocketmuse

import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun LibraryStorageScreen(onBack: () -> Unit, vm: LibraryStorageViewModel=viewModel()) {
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val size by vm.size.collectAsState()
    val restore by vm.restore.collectAsState()
    val retention by vm.retention.collectAsState()
    val ready by vm.ready.collectAsState()
    var passwordDialog by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var age by rememberSaveable { mutableIntStateOf(1) }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri==null) vm.cancelBackup() else vm.save(uri)
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) { restoreUri=uri.toString();passwordDialog=true }
    }
    LaunchedEffect(Unit) { vm.refresh() }
    fun back() { if(!busy) { vm.leave();onBack() } }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        TextButton(onClick={ back() },enabled=!busy) { Text("Back to knowledge & projects") }
        Text("Storage & backup",style=MaterialTheme.typography.headlineMedium,color=Ink)
        Text("Your library stays on this phone until you choose to export it. Nothing is deleted automatically.",color=Muted)
        size?.let { Text("${it.sources} sources · ${it.facts} facts · ${it.projects} projects\n${"%.1f".format(it.originalBytes/1048576.0)} MiB of original files",color=Ink) }
        if(busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Working on your library…",color=Muted) }
        if(message.isNotBlank()) Text(message,color=Ink)
        HorizontalDivider()
        SectionTitle("Encrypted backup")
        Text("Includes Inbox originals, suggestions, knowledge with history, and projects. Chats, older notes and memories, models, settings and connection secrets are excluded. Limit: 32 MiB per library backup.",color=Muted)
        Text("Choose a strong passphrase and keep it separately. There is no recovery key or password reset. The phone’s live database uses Android’s device protection; this passphrase protects exported backups.",color=Muted)
        Button(enabled=!busy && !ready,onClick={ restoreUri=null;passwordDialog=true }) { Text("Prepare encrypted backup") }
        if(ready) {
            Text("Encrypted backup is ready. Choose a destination; a cloud file provider may upload the encrypted file.")
            Button(enabled=!busy,onClick={ create.launch("cina-library.cinabackup") }) { Text("Save backup file") }
            TextButton(enabled=!busy,onClick={ vm.cancelBackup() }) { Text("Cancel backup") }
        }
        OutlinedButton(enabled=!busy && !ready,onClick={ open.launch(arrayOf("*/*")) }) { Text("Open backup to restore") }
        HorizontalDivider()
        SectionTitle("Keep only what you need")
        Text("Preview sources by capture age, then confirm removal. Their originals, suggestions, linked facts and histories will be deleted, even if recently reviewed. Projects remain. Pending source-linked tasks are cancelled; task details and completed results remain. Deletion does not erase separate backup files or guarantee forensic erasure from device storage.",color=Muted)
        ScreenTabs(listOf("30 days","90 days","1 year"),age) { age=it }
        OutlinedButton(enabled=!busy,onClick={ vm.previewCleanup(listOf(30,90,365)[age]) }) { Text("Preview older sources") }
    }
    if(passwordDialog) {
        // Deliberately not saveable: passwords must not enter saved-instance state.
        var password by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        val restoring=restoreUri!=null
        AlertDialog(onDismissRequest={ passwordDialog=false;restoreUri=null },title={ Text(if(restoring) "Unlock backup" else "Protect your backup") },text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(restoring) "Enter the backup passphrase to validate it and see a preview." else "Use at least 12 characters. Keep this passphrase safely; it cannot be recovered.")
                OutlinedTextField(password,{ if(it.length<=1024) password=it },label={ Text("Passphrase") },visualTransformation=PasswordVisualTransformation(),singleLine=true)
                if(!restoring) OutlinedTextField(confirm,{ if(it.length<=1024) confirm=it },label={ Text("Repeat passphrase") },visualTransformation=PasswordVisualTransformation(),singleLine=true)
            }
        },confirmButton={ TextButton(enabled=password.length in 12..1024 && (restoring || password==confirm),onClick={
            val chars=password.toCharArray();password="";confirm="";passwordDialog=false
            if(restoring) vm.inspect(restoreUri!!.toUri(),chars) else vm.prepare(chars)
            restoreUri=null
        }) { Text(if(restoring) "Validate backup" else "Encrypt backup") } },dismissButton={ TextButton(onClick={ passwordDialog=false;restoreUri=null }) { Text("Cancel") } })
    }
    restore?.let { preview -> AlertDialog(onDismissRequest={ if(!busy) vm.cancelRestore() },title={ Text("Replace this library?") },text={
        Text("Verified backup: ${preview.size.sources} sources, ${preview.size.facts} facts and ${preview.size.projects} projects.\n\nThis replaces all current Inbox sources, suggestions, knowledge, histories and projects. Current items absent from the backup will be removed. Export your current library first if you want to keep it. Pending tasks linked to replaced sources are cancelled; task details and completed results remain. Chats, models and settings remain. No actions will run.")
    },confirmButton={ TextButton(enabled=!busy,onClick={ vm.applyRestore() }) { Text("Replace library") } },dismissButton={ TextButton(enabled=!busy,onClick={ vm.cancelRestore() }) { Text("Cancel") } }) }
    retention?.let { preview -> AlertDialog(onDismissRequest={ if(!busy) vm.cancelCleanup() },title={ Text("Remove ${preview.ids.size} older sources?") },text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            item { Text("${preview.facts} linked facts and their histories will also be removed. This cannot be undone without a backup.") }
            items(preview.titles) { Text("• $it",modifier=Modifier.padding(top=8.dp)) }
            if(preview.ids.isEmpty()) item { Text("No sources match this age.") }
        }
    },confirmButton={ TextButton(enabled=!busy && preview.ids.isNotEmpty(),onClick={ vm.cleanup() }) { Text("Delete these sources") } },dismissButton={ TextButton(enabled=!busy,onClick={ vm.cancelCleanup() }) { Text("Cancel") } }) }
}
