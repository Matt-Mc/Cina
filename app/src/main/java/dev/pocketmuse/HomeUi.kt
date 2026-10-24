package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Existing screens embed these rows; Home has no navigation destination of its own. */
@Composable internal fun HomeRefresh(vm: AssistantViewModel) {
    LaunchedEffect(vm) { while (true) { vm.refreshHome(); delay(5000) } }
}

@Composable internal fun HomeConnectionRow(vm: AssistantViewModel, available: Boolean) {
    val home by vm.homeState.collectAsState()
    val jobs by vm.homeJobs.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    var bundle by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val status by vm.status.collectAsState()
    val states by vm.connectionStates.collectAsState()
    HomeRefresh(vm)
    LaunchedEffect(home.paired) { if(home.paired) { pairing = false; bundle = ""; submitted = false } }
    if (home.paired) OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(home.name, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(if(home.online) home.detail else home.detail.ifBlank { "Last known state" }, color = Muted, fontSize = 12.sp)
        Text("${home.models.size} models · ${jobs.count { !it.terminal }} saved jobs · ${if(home.paused) "New work paused" else "Accepting work"}", color = Muted, fontSize = 12.sp)
        Text("Models and downloads are in Models. Job progress and controls are in Work.", color = Muted, fontSize = 12.sp)
        Row {
            TextButton(onClick = { vm.pauseHome(!home.paused) }) { Text(if(home.paused) "Allow new work" else "Pause new work") }
            TextButton(onClick = { disconnect = true }) { Text("Disconnect") }
        }
        Text("Pausing new work lets the current job finish. The control connection stays available.", color = Muted, fontSize = 11.sp)
    } } else if(available) TextButton(onClick = { pairing = true; submitted = false }) { Text("Add your Home server") }
    if (pairing) AlertDialog(onDismissRequest = { if(!submitted) pairing = false }, title = { Text("Connect Home") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Deploy the Cina companion, then paste the pairing details generated on your server. They include its address, certificate fingerprint and a code that expires after 10 minutes.", color = Muted)
            Text("Only use pairing details from your own server. Chat excerpts and attached passages are shared with Home when you choose a Home model.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(bundle, { bundle = it; submitted = false }, label = { Text("Pairing details") }, minLines = 3,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            if(submitted && status.isNotBlank()) Text(status, color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { submitted = true; vm.pairHome(bundle) }, enabled = bundle.isNotBlank() && states["home"] != "Connecting…") { Text(if(states["home"] == "Connecting…") "Connecting…" else "Connect") } },
        dismissButton = { TextButton(onClick = { pairing = false; bundle = "" }) { Text("Cancel") } })
    if(disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect Home?") }, text = {
        Column {
            Text("Accepted jobs continue on the server. Cancel unfinished work first if needed. Disconnect revokes this phone's access. A new pairing cannot recover this pairing's jobs.")
            Text("If Home is unreachable or its certificate changed, forgetting removes only the saved connection. It does not revoke access or stop work; revoke the device on your host if needed.", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { vm.forgetHome(); disconnect = false }) { Text("Forget on this phone") }
        }
    }, confirmButton = { TextButton(onClick = { vm.disconnectHome(); disconnect = false }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Keep connected") } })
}

@Composable internal fun HomeModelRows(vm: AssistantViewModel, discover: Boolean) {
    val home by vm.homeState.collectAsState()
    val jobs by vm.homeJobs.collectAsState()
    var remove by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf("") }
    if (!home.paired) return
    HomeRefresh(vm)
    Text(home.name, color = Ink, fontWeight = FontWeight.Medium)
    Text("Home models answer and analyse. Use a phone model for device actions and connected tools. Work continues when you close Cina.", color = Muted, fontSize = 12.sp)
    if (!home.online) Text(home.detail, color = Muted, fontSize = 12.sp)
    if(discover) {
        Text("Start with 4B on an 8 GB graphics card; test 8B after that. Speed and memory use depend on your hardware.", color = Muted, fontSize = 12.sp)
        home.catalog.forEach { name -> PaperRow(name, "Home · local model download", if(home.models.any { it.name == name }) "Installed" else "Get") {
            if(home.models.none { it.name == name }) vm.homeModelAction("download", name)
        } }
        OutlinedTextField(custom, { custom = it }, label = { Text("Local Ollama model name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.homeModelAction("download", custom.trim()); custom = "" }, enabled = custom.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}"))) { Text("Download on Home") }
        Text("Downloads and their progress appear in Work.", color = Muted, fontSize = 12.sp)
    } else {
        if(home.models.isEmpty()) Text("No Home models installed. Find one in Discover.", color = Muted)
        home.models.forEach { model ->
            val inUse = jobs.any { it.model == model.name && it.state in setOf("waiting", "queued", "running", "pausing", "cancelling") }
            PaperRow(model.name, "Home · ${sizeLabel(model.bytes)}${if(model.loaded) " · Loaded" else ""}", if(home.selected == model.name) "Selected" else "Use") { vm.setHomeModel(model.name) }
            Row {
                TextButton(onClick = { vm.assignHomeDeep(if(home.deepModel == model.name) null else model.name) }) { Text(if(home.deepModel == model.name) "Deep model ✓" else "Use for Deep") }
                TextButton(onClick = { vm.homeModelAction(if(model.loaded) "unload" else "load", model.name) }, enabled = !inUse) { Text(if(model.loaded) "Unload" else "Load") }
                TextButton(onClick = { remove = model.name }, enabled = !inUse && home.selected != model.name && home.deepModel != model.name) { Text("Remove") }
            }
        }
    }
    HorizontalDivider(color = Line)
    remove?.let { name -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove $name from Home?") }, text = { Text("You can download it again later. Existing results stay in Work.") },
        confirmButton = { TextButton(onClick = { vm.homeModelAction("delete", name); remove = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable internal fun HomeWorkRows(vm: AssistantViewModel, attention: Boolean = false) {
    val jobs by vm.homeJobs.collectAsState()
    val home by vm.homeState.collectAsState()
    var viewed by remember { mutableStateOf<String?>(null) }
    var showOlder by remember { mutableStateOf(false) }
    val shown = jobs.filter { (it.state in setOf("failed", "interrupted") || it.error.isNotBlank()) == attention }
    HomeRefresh(vm)
    (if(showOlder) shown else shown.take(30)).forEach { job -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(job.title, color = Ink, fontWeight = FontWeight.Medium)
        Text("${job.state.replaceFirstChar(Char::uppercase)} · Home · ${job.model}", color = Muted, fontSize = 12.sp)
        Text(job.command?.let { "$it requested · waiting for Home" } ?: job.error.ifBlank { job.snapshot.optString("detail") }, color = Muted, fontSize = 12.sp)
        if(!home.online && !job.terminal) Text("Last known state. Home may still be working.", color = Muted, fontSize = 12.sp)
        Row {
            if(job.state in setOf("waiting", "queued", "running") && job.kind in setOf("analysis", "download")) TextButton(onClick = { vm.controlHomeJob(job.id, "pause") }, enabled = job.command == null) { Text("Pause") }
            if(job.state in setOf("paused", "interrupted", "failed") && job.kind in setOf("analysis", "download")) TextButton(onClick = { vm.controlHomeJob(job.id, "resume") }, enabled = job.command == null) { Text("Resume") }
            if(!job.terminal) TextButton(onClick = { vm.controlHomeJob(job.id, "cancel") }, enabled = job.command != "cancel") { Text("Cancel") }
            if(job.output.isNotBlank()) TextButton(onClick = { viewed = job.id }) { Text(if(job.state == "completed") "Result" else "Partial result") }
        }
        if(job.state in setOf("paused", "interrupted")) Text("Resume starts the analysis again. Downloads reuse already downloaded layers.", color = Muted, fontSize = 11.sp)
    } } }
    if(shown.size > 30) TextButton(onClick = { showOlder = !showOlder }) { Text(if(showOlder) "Show recent Home work" else "Show older Home work") }
    viewed?.let { id -> jobs.firstOrNull { it.id == id }?.let { job -> AlertDialog(onDismissRequest = { viewed = null }, title = { Text(job.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { Text(job.output) }
    }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Close") } }) } }
}
