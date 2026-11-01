package dev.pocketmuse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ModelsScreen(vm: AssistantViewModel, setup: Boolean = false, onReady: () -> Unit = {}) {
    val models by vm.models.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val download by vm.download.collectAsState()
    val resumable by vm.resumable.collectAsState()
    val files by vm.modelFiles.collectAsState()
    val initialSelection = remember { selected }
    LaunchedEffect(selected) { if(setup && selected != initialSelection && models.any { it.path == selected }) onReady() }
    Page("Phone model", "Choose one model for on-device chat. Downloads stay on your phone.") {
        if(download.running) {
            Text("Downloading ${download.name}", color = Ink)
            LinearProgressIndicator(progress = { (download.received.toFloat()/download.total.coerceAtLeast(1)).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth(),color=Accent)
            Text("${sizeLabel(download.received)} of ${sizeLabel(download.total)}",color=Muted)
            TextButton(onClick=vm::cancelDownload) { Text("Pause download") }
        }
        if(download.error.isNotBlank()) Text(download.error,color=MaterialTheme.colorScheme.error)
        if(resumable!=null && !download.running) OutlinedButton(onClick={ vm.download(resumable!!.first,resumable!!.second) }) { Text("Resume download") }
        PhoneModelsPane(vm, ModelSlot.PHONE, selected)
    }
    if(files.repo!=null) AlertDialog(onDismissRequest=vm::closeModelFiles,containerColor=Paper,title={ Text("Choose a model file") },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ModelFileChoices(files,ModelSlot.PHONE,models,download.running,
                { vm.library.canDownload(ModelSlot.PHONE,it.size) },{ vm.library.fitsSlot(ModelSlot.PHONE,it.size) },
                { vm.openRepo(files.repo!!) },{ vm.download(it.url,it.name,ModelSlot.PHONE);vm.closeModelFiles() })
        }
    },confirmButton={ TextButton(onClick=vm::closeModelFiles) { Text("Close") } })
}

@Composable
private fun PhoneModelsPane(vm: AssistantViewModel, slot: ModelSlot, assigned: String?) {
    val models by vm.models.collectAsState(); val popular by vm.popularModels.collectAsState()
    val endpoint by vm.endpoint.collectAsState()
    val loading by vm.popularModelsLoading.collectAsState()
    val busy by vm.busy.collectAsState(); val benchmarking by vm.benchmarking.collectAsState()
    val benchmarks by vm.benchmarks.collectAsState(); val progress by vm.phoneBenchmarkProgress.collectAsState()
    val download by vm.download.collectAsState()
    var adding by rememberSaveable { mutableStateOf(models.isEmpty()) }
    var advanced by rememberSaveable(slot) { mutableStateOf(false) }; var viewed by remember { mutableStateOf<LocalModel?>(null) }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }; var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.import(uri) }
    LaunchedEffect(slot) { if(popular.isEmpty()) vm.refreshPopularModels() }
    Text("${sizeLabel(vm.library.freeBytes())} free on phone", color = Muted, fontSize = 12.sp)
    SectionTitle("Downloaded")
    val suitable = models.sortedBy { it.name.lowercase() }
    if(suitable.isEmpty()) Text("No phone model installed. Download one or import a GGUF file.", color = Muted)
    suitable.forEach { model ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { viewed = model }.padding(vertical = 10.dp)) {
                Text(model.name, color = Ink, fontWeight = FontWeight.Medium)
                Text("${sizeLabel(model.bytes)}${if(model.path == assigned) " · Selected phone model" else ""}", color = Muted, fontSize = 12.sp)
                if(!vm.library.fitsSlot(slot, model.bytes)) Text("Above this phone's estimated memory limit", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { vm.choosePhoneModel(slot, model) }, enabled = (model.path != assigned || endpoint.enabled) && !busy && benchmarking == null) { Text(if(model.path == assigned && !endpoint.enabled) "Selected" else "Use") }
        }
        HorizontalDivider(color = Line)
    }
    TextButton(onClick={ adding=!adding }) { Text(if(adding) "Close downloads" else "Add a model") }
    if(adding) {
    HubModelSearch(vm, slot)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Available downloads", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
        TextButton(onClick = vm::refreshPopularModels, enabled = !loading) { Text(if(loading) "Loading…" else "Refresh") }
    }
    Text("Filtered to this phone's estimated limits. Check speed and answer quality on your phone after downloading.", color = Muted, fontSize = 12.sp)
    if(loading && popular.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    val candidates = popular.mapNotNull { model -> model.files.filter { vm.library.fitsSlot(slot, it.size) }
        .sortedWith(compareBy<RemoteModel> { if(it.file.contains("Q4_K_M", true)) 0 else 1 }.thenBy { if(slot == ModelSlot.PHONE) it.size else -it.size })
        .firstOrNull()?.let { model to it } }.take(3)
    candidates.forEach { (model, file) ->
        Text(model.repo, color = Ink, fontWeight = FontWeight.Medium)
        Text("${sizeLabel(file.size)} · ${file.file.substringAfterLast('/')}", color = Muted, fontSize = 12.sp)
        Row {
            val installed = models.any { it.source == file.url }
            TextButton(onClick = { vm.download(file.url, file.name, slot) }, enabled = !installed && !download.running && vm.library.canDownload(slot, file.size)) { Text(if(installed) "Downloaded" else "Download to phone") }
            TextButton(onClick = { vm.openRepo(model.repo) }) { Text("Choose file") }
        }
        HorizontalDivider(color = Line)
    }
    if(candidates.isEmpty() && !loading) Text("Search for a model above or refresh the list.", color = Muted, fontSize = 12.sp)
    TextButton(onClick = { advanced = !advanced }) { Text(if(advanced) "Hide more options" else "More options") }
    if(advanced) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a GGUF file") }
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS GGUF link") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Model name") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.download(url, name, slot) }, enabled = url.startsWith("https://") && name.isNotBlank() && !download.running) { Text("Download to phone") }
        Text("Hugging Face access token · for gated models you can access", color = Muted, fontSize = 12.sp)
        OutlinedTextField(token, { token = it }, label = { Text("Hugging Face token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.setKey("hf", token); token = "" }, enabled = token.isNotBlank()) { Text("Save token") }
        if(progress != null) { Text("Checking ${progress!!.first} of ${progress!!.second} · keep Cina open", color = Muted); TextButton(onClick = vm::cancelPhoneBenchmark) { Text("Stop") } }
    }
    }
    viewed?.let { model -> AlertDialog(onDismissRequest = { viewed = null }, title = { Text(model.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${sizeLabel(model.bytes)} · ${vm.library.suitability(model.bytes)}")
            benchmarks.firstOrNull { it.modelPath == model.path }?.let { Text(it.result, fontSize = 12.sp) }
            TextButton(onClick = { vm.runBenchmark(model) }, enabled = !busy && benchmarking == null && vm.library.isPhoneAgentSize(model.bytes)) { Text("Test model performance") }
            TextButton(onClick = { deleting = model; viewed = null }) { Text("Remove from phone") }
        }
    }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Close") } }) }
    deleting?.let { model -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${model.name}?") },
        confirmButton = { TextButton(onClick = { vm.removeModel(model); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun HubModelSearch(vm: AssistantViewModel, slot: ModelSlot) {
    val repos by vm.repoResults.collectAsState()
    val loading by vm.modelSearchLoading.collectAsState()
    val error by vm.modelSearchError.collectAsState()
    var query by rememberSaveable(slot) { mutableStateOf("") }
    var searched by rememberSaveable(slot) { mutableStateOf(false) }
    LaunchedEffect(slot) { vm.clearModelSearch(); searched = false }
    SectionTitle("Find a model")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, label = { Text("Search Hugging Face") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { searched = true; vm.searchRepos(query.trim()) }, enabled = query.isNotBlank() && !loading) { Text("Search") }
    }
    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if(searched && !loading && error.isBlank() && repos.isEmpty()) Text("No model repositories found. Try another name.", color = Muted)
    repos.forEach { repo -> PaperRow(repo, "Choose a GGUF file for this phone", "Choose file") { vm.openRepo(repo) } }
}

@Composable
internal fun ModelFileChoices(state: ModelFilesState, slot: ModelSlot, installed: List<LocalModel>, downloading: Boolean,
    canDownload: (RemoteModel) -> Boolean, fits: (RemoteModel) -> Boolean, onRetry: () -> Unit, onDownload: (RemoteModel) -> Unit) {
    Text(state.repo.orEmpty(), color = Ink, fontWeight = FontWeight.Medium)
    Text("Download a GGUF file for on-device chat.", color = Muted, fontSize = 12.sp)
    if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    if(state.error.isNotBlank()) { Text(state.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Try again") } }
    if(!state.loading && state.error.isBlank()) {
        val suitable = state.files.filter(fits)
        if(suitable.isEmpty()) Text(when {
            state.files.isEmpty() -> "This repository has no supported single-file GGUF models. Try another repository."
            else -> "No file fits this phone's estimated memory limit. Try a smaller model."
        }, color = Muted)
        suitable.forEach { file ->
            Text(file.file, color = Ink, fontSize = 13.sp); Text(sizeLabel(file.size), color = Muted, fontSize = 12.sp)
            val exists = installed.any { it.source == file.url }
            Button(onClick = { onDownload(file) }, enabled = !exists && !downloading && canDownload(file)) { Text(if(exists) "Downloaded" else "Download to phone") }
            if(!exists && !canDownload(file)) Text("Not enough free phone storage for this file.", color = Muted, fontSize = 12.sp)
            HorizontalDivider(color = Line)
        }
    }
}

internal fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
