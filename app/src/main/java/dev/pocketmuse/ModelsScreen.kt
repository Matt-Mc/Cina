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
internal fun ModelsScreen(vm: AssistantViewModel, slot: ModelSlot?, onSlot: (ModelSlot?) -> Unit, onConnectHome: () -> Unit) {
    val models by vm.models.collectAsState(); val selected by vm.selectedModel.collectAsState()
    val assignments by vm.modelAssignments.collectAsState(); val home by vm.homeState.collectAsState()
    val jobs by vm.homeJobs.collectAsState(); val download by vm.download.collectAsState()
    val currentJobs = jobs.filter { it.server == home.serverId && it.device == home.deviceId }
    val resumable by vm.resumable.collectAsState(); val files by vm.modelFiles.collectAsState()
    var removeHome by remember { mutableStateOf<String?>(null) }
    HomeRefresh(vm)
    LaunchedEffect(Unit) { vm.clearStatus() }
    val phone = models.firstOrNull { it.path == (assignments[ResponseMode.QUICK] ?: selected) }
    val heavy = models.firstOrNull { it.path == assignments[ResponseMode.DEEP] }
    key(slot) { Page(slot?.title ?: "Models", slot?.description ?: "Three choices. Choose a model for each kind of work.") {
        when(slot) {
            null -> {
                ModelSlotOverview(phone?.name, heavy?.name, home.deepModel ?: home.selected, home.paired, onSlot)
                Text("Phone models work offline. Home handles larger analysis when your server is available.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            }
            ModelSlot.HOME -> { HomeModelsPane(home, currentJobs, vm::assignHomeDeep,
                { vm.homeModelAction("download", it) }, { name, loaded -> vm.homeModelAction(if(loaded) "unload" else "load", name) },
                { removeHome = it }, onConnectHome)
                if(home.paired && home.hubModels) HubModelSearch(vm, slot)
            }
            else -> PhoneModelsPane(vm, slot, if(slot == ModelSlot.PHONE) phone?.path else heavy?.path ?: phone?.path)
        }
        if(download.running) {
            Text("Downloading to phone · ${download.name}", color = Ink)
            LinearProgressIndicator(progress = { (download.received.toFloat() / download.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Accent)
            Text("${sizeLabel(download.received)} of ${sizeLabel(download.total)}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = vm::cancelDownload) { Text("Pause download") }
        }
        if(download.error.isNotBlank()) Text(download.error, color = MaterialTheme.colorScheme.error)
        if(resumable != null && !download.running) TextButton(onClick = { vm.download(resumable!!.first, resumable!!.second) }) { Text("Resume phone download") }
    } }
    if(slot != null && files.repo != null) AlertDialog(onDismissRequest = vm::closeModelFiles,
        containerColor = Paper, title = { Text("Choose a model file") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelFileChoices(files, slot, models, download.running,
                    { if(slot == ModelSlot.HOME) home.hubModels && ModelChoices.homeName(it) != null else vm.library.canDownload(slot, it.size) },
                    { if(slot == ModelSlot.HOME) ModelChoices.homeName(it) != null else vm.library.fitsSlot(slot, it.size) },
                    { vm.openRepo(files.repo!!) }, {
                        if(slot == ModelSlot.HOME) ModelChoices.homeName(it)?.let { name -> vm.homeModelAction("download", name) }
                        else vm.download(it.url, it.name, slot)
                        vm.closeModelFiles()
                    }, homeInstalled = home.models.map { it.name }.toSet(), homePending = currentJobs.filter { it.kind == "download" && !it.terminal }.map { it.model }.toSet())
            }
        }, confirmButton = { TextButton(onClick = vm::closeModelFiles) { Text("Close") } })
    removeHome?.let { name -> AlertDialog(onDismissRequest = { removeHome = null }, title = { Text("Remove $name from Home?") },
        text = { Text("You can download it again later. Existing results stay in Work.") },
        confirmButton = { TextButton(onClick = { vm.homeModelAction("delete", name); removeHome = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removeHome = null }) { Text("Cancel") } }) }
}

@Composable
internal fun ModelSlotOverview(phone: String?, heavy: String?, home: String?, paired: Boolean, onOpen: (ModelSlot) -> Unit) {
    val names = mapOf(ModelSlot.PHONE to phone, ModelSlot.HEAVY_PHONE to (heavy ?: phone), ModelSlot.HOME to home?.let(ModelChoices::homeLabel))
    ModelSlot.entries.forEachIndexed { index, slot ->
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Choose ${slot.title}" }
            .clickable { onOpen(slot) }.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("0${index + 1}", Modifier.width(36.dp), color = Accent, fontSize = 12.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(slot.title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(names[slot] ?: if(slot == ModelSlot.HOME && !paired) "Connect your server" else "Choose a model", color = Muted, fontSize = 14.sp)
                if(slot == ModelSlot.HEAVY_PHONE && heavy == null && phone != null) Text("Using your phone model until you choose another", color = Muted, fontSize = 11.sp)
            }
            Text("›", Modifier.padding(start = 12.dp), color = Accent, fontSize = 26.sp)
        }
        HorizontalDivider(color = Line)
    }
}

@Composable
private fun PhoneModelsPane(vm: AssistantViewModel, slot: ModelSlot, assigned: String?) {
    val models by vm.models.collectAsState(); val popular by vm.popularModels.collectAsState()
    val loading by vm.popularModelsLoading.collectAsState()
    val busy by vm.busy.collectAsState(); val benchmarking by vm.benchmarking.collectAsState()
    val benchmarks by vm.benchmarks.collectAsState(); val progress by vm.phoneBenchmarkProgress.collectAsState()
    val download by vm.download.collectAsState()
    var advanced by rememberSaveable(slot) { mutableStateOf(false) }; var viewed by remember { mutableStateOf<LocalModel?>(null) }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }; var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }; var token by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.import(uri) }
    LaunchedEffect(slot) { if(popular.isEmpty()) vm.refreshPopularModels() }
    Text("${sizeLabel(vm.library.freeBytes())} free on phone", color = Muted, fontSize = 12.sp)
    SectionTitle("Downloaded")
    val suitable = models.filter { vm.library.fitsSlot(slot, it.bytes) || it.path == assigned }.sortedBy { if(slot == ModelSlot.PHONE) it.bytes else -it.bytes }
    if(suitable.isEmpty()) Text("No suitable model downloaded yet. Choose one below.", color = Muted)
    suitable.forEach { model ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { viewed = model }.padding(vertical = 10.dp)) {
                Text(model.name, color = Ink, fontWeight = FontWeight.Medium)
                Text("${sizeLabel(model.bytes)}${if(model.path == assigned) " · In use" else ""}", color = Muted, fontSize = 12.sp)
                if(!vm.library.fitsSlot(slot, model.bytes)) Text("Above this choice's estimated size range", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { vm.choosePhoneModel(slot, model) }, enabled = model.path != assigned && !busy && benchmarking == null) { Text(if(model.path == assigned) "In use" else "Use") }
        }
        HorizontalDivider(color = Line)
    }
    if(slot == ModelSlot.HEAVY_PHONE) Text("A larger phone model can use more memory and battery. The same model can fill both phone choices.", color = Muted, fontSize = 12.sp)
    HubModelSearch(vm, slot)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Popular choices to try", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
        TextButton(onClick = vm::refreshPopularModels, enabled = !loading) { Text(if(loading) "Loading…" else "Refresh") }
    }
    Text("Filtered to this phone's estimated limits. Check speed and answer quality on your phone after downloading.", color = Muted, fontSize = 12.sp)
    if(loading && popular.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    val candidates = popular.mapNotNull { model -> model.files.filter { vm.library.fitsSlot(slot, it.size) }
        .sortedWith(compareBy<RemoteModel> { if(it.file.contains("Q4_K_M", true)) 0 else 1 }.thenBy { if(slot == ModelSlot.PHONE) it.size else -it.size })
        .firstOrNull()?.let { model to it } }.take(8)
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
    if(candidates.isEmpty() && !loading) Text("Search for a model above or refresh the live list.", color = Muted, fontSize = 12.sp)
    TextButton(onClick = { advanced = !advanced }) { Text(if(advanced) "Hide more options" else "More options") }
    if(advanced) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import a GGUF file") }
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS GGUF link") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Model name") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.download(url, name, slot) }, enabled = url.startsWith("https://") && name.isNotBlank() && !download.running) { Text("Download to phone") }
        Text("Hugging Face access token · for gated models you can access", color = Muted, fontSize = 12.sp)
        OutlinedTextField(token, { token = it }, label = { Text("Hugging Face token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.setKey("hf", token); token = "" }, enabled = token.isNotBlank()) { Text("Save token") }
        TextButton(onClick = vm::benchmarkPhoneModels, enabled = !busy && benchmarking == null && !download.running && models.any { vm.library.isPhoneAgentSize(it.bytes) }) { Text("Check speed and skills") }
        if(progress != null) { Text("Checking ${progress!!.first} of ${progress!!.second} · keep Cina open", color = Muted); TextButton(onClick = vm::cancelPhoneBenchmark) { Text("Stop") } }
    }
    viewed?.let { model -> AlertDialog(onDismissRequest = { viewed = null }, title = { Text(model.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${sizeLabel(model.bytes)} · ${vm.library.suitability(model.bytes)}")
            benchmarks.firstOrNull { it.modelPath == model.path }?.let { Text(it.result, fontSize = 12.sp) }
            TextButton(onClick = { vm.runBenchmark(model) }, enabled = !busy && benchmarking == null && vm.library.isPhoneAgentSize(model.bytes)) { Text("Check speed and skills") }
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
    SectionTitle(if(slot == ModelSlot.HOME) "Hugging Face models" else "Find a model")
    if(slot == ModelSlot.HOME) Text("Choose a GGUF file to download directly on Home. Private or gated models need access configured on your server.", color = Muted, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, label = { Text("Search Hugging Face") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { searched = true; vm.searchRepos(query.trim()) }, enabled = query.isNotBlank() && !loading) { Text("Search") }
    }
    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    if(searched && !loading && error.isBlank() && repos.isEmpty()) Text("No model repositories found. Try another name.", color = Muted)
    repos.forEach { repo -> PaperRow(repo, if(slot == ModelSlot.HOME) "Choose a GGUF file for Home" else "Choose a GGUF file for this phone", "Choose file") { vm.openRepo(repo) } }
}

@Composable
internal fun ModelFileChoices(state: ModelFilesState, slot: ModelSlot, installed: List<LocalModel>, downloading: Boolean,
    canDownload: (RemoteModel) -> Boolean, fits: (RemoteModel) -> Boolean, onRetry: () -> Unit, onDownload: (RemoteModel) -> Unit, homeInstalled: Set<String> = emptySet(), homePending: Set<String> = emptySet()) {
    Text(state.repo.orEmpty(), color = Ink, fontWeight = FontWeight.Medium)
    Text(if(slot == ModelSlot.HOME) "Downloads go directly to your Home server." else "Downloads go to your phone for ${slot.title.lowercase()}.", color = Muted, fontSize = 12.sp)
    if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
    if(state.error.isNotBlank()) { Text(state.error, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("Try again") } }
    if(!state.loading && state.error.isBlank()) {
        val suitable = state.files.filter(fits)
        if(suitable.isEmpty()) Text(when {
            state.files.isEmpty() -> "This repository has no supported single-file GGUF models. Try another repository."
            slot == ModelSlot.HOME -> "Home supports single GGUF files in the repository root, with filenames up to 80 characters. Choose another repository."
            else -> "No file fits this choice's estimated phone memory limit. Try a smaller model${if(slot == ModelSlot.PHONE) " or Heavy phone model" else ""}."
        }, color = Muted)
        suitable.forEach { file ->
            Text(file.file, color = Ink, fontSize = 13.sp); Text(sizeLabel(file.size), color = Muted, fontSize = 12.sp)
            val homeName = ModelChoices.homeName(file)
            val exists = if(slot == ModelSlot.HOME) homeName in homeInstalled else installed.any { it.source == file.url }
            val pending = slot == ModelSlot.HOME && homeName in homePending
            Button(onClick = { onDownload(file) }, enabled = !exists && !pending && (slot == ModelSlot.HOME || !downloading) && canDownload(file)) { Text(if(pending) "Queued on Home" else if(exists) "Downloaded" else if(slot == ModelSlot.HOME) "Download on Home" else "Download to phone") }
            if(!exists && !canDownload(file)) Text(if(slot == ModelSlot.HOME) "This file is not supported by Home's runner." else "Not enough free phone storage for this file.", color = Muted, fontSize = 12.sp)
            HorizontalDivider(color = Line)
        }
    }
}

internal fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
