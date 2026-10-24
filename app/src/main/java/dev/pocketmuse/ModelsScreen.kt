package dev.pocketmuse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ModelsScreen(vm: AssistantViewModel) {
    val models by vm.models.collectAsState(); val selected by vm.selectedModel.collectAsState()
    val home by vm.homeState.collectAsState()
    val download by vm.download.collectAsState(); val benchmarks by vm.benchmarks.collectAsState()
    val benchmarking by vm.benchmarking.collectAsState(); val busy by vm.busy.collectAsState()
    val resumable by vm.resumable.collectAsState(); val repos by vm.repoResults.collectAsState(); val files by vm.remoteFiles.collectAsState()
    val phoneBenchmarkProgress by vm.phoneBenchmarkProgress.collectAsState()
    val popularModels by vm.popularModels.collectAsState(); val popularLoading by vm.popularModelsLoading.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (models.isEmpty() && home.models.isEmpty()) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var directUrl by rememberSaveable { mutableStateOf("") }; var directName by rememberSaveable { mutableStateOf("") }
    var hf by remember { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var access by rememberSaveable { mutableStateOf(false) }
    var viewed by remember { mutableStateOf<LocalModel?>(null) }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }
    var assignmentRevision by remember { mutableIntStateOf(0) }
    val benchmarkableModels = remember(models, vm.library) { models.filter { vm.library.isPhoneAgentSize(it.bytes) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    LaunchedEffect(tab) { if (tab == 1 && popularModels.isEmpty()) vm.refreshPopularModels() }
    Page("Models", "Choose a model for your phone or your Home server.") {
        ScreenTabs(listOf("Installed", "Discover"), tab) { tab = it }
        HomeModelRows(vm, tab == 1)
        Text("${sizeLabel(vm.library.freeBytes())} free · ${sizeLabel(vm.library.totalRam())} memory", color = Muted, fontSize = 12.sp)
        if (models.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Benchmark for this phone")
            Text("Tests speed and five practical agent skills on models sized for this phone. Quick needs 4/5 checks and 8+ tokens/s; Deep needs 4/5 and 3+ tokens/s.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Button(onClick = vm::benchmarkPhoneModels, enabled = benchmarkableModels.isNotEmpty() && phoneBenchmarkProgress == null && benchmarking == null && !busy && !download.running) {
                Text(if (phoneBenchmarkProgress != null) "Benchmarking ${phoneBenchmarkProgress!!.first} of ${phoneBenchmarkProgress!!.second}…" else "Benchmark this phone")
            }
            phoneBenchmarkProgress?.let { (current, total) ->
                LinearProgressIndicator(progress = { current.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val currentName = models.firstOrNull { it.path == benchmarking }?.name ?: "Model $current of $total"
                    Text("$currentName · keep Cina open", Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                    TextButton(onClick = vm::cancelPhoneBenchmark) { Text("Stop") }
                }
            }
            if (benchmarkableModels.isEmpty()) Text("No installed model fits the phone-size agent range yet. Choose a candidate in Discover, then benchmark it here.", color = Muted, fontSize = 12.sp)
        }
        if (download.running) {
            Text("Downloading ${download.name}", color = Ink)
            LinearProgressIndicator(progress = { (download.received.toFloat() / download.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Accent)
            Text("${sizeLabel(download.received)} of ${sizeLabel(download.total)}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = vm::cancelDownload) { Text("Pause download") }
        }
        if (download.error.isNotBlank()) Text(download.error, color = MaterialTheme.colorScheme.error)
        if (resumable != null && !download.running) Button(onClick = { vm.download(resumable!!.first, resumable!!.second) }) { Text("Resume ${resumable!!.second}") }
        if (tab == 0) {
            val installedBenchmarks = benchmarks.filter { result -> models.any { it.path == result.modelPath } }
            val eligibleBenchmarks = installedBenchmarks.filter { result ->
                val model = models.firstOrNull { it.path == result.modelPath }
                vm.library.isPhoneAgentSize(model?.bytes ?: -1L) && benchmarkHasAgentChecks(result.result) && (benchmarkTaskScore(result.result) ?: 0) >= 4
            }
            val quickPool = eligibleBenchmarks.filter { (benchmarkTokensPerSecond(it.result) ?: 0.0) >= 8.0 }
            val deepPool = eligibleBenchmarks.filter { (benchmarkTokensPerSecond(it.result) ?: 0.0) >= 3.0 }
            val fastest = quickPool.maxByOrNull { benchmarkTokensPerSecond(it.result) ?: -1.0 }
            val bestOverall = deepPool.maxWithOrNull(compareBy<ModelBenchmark> { benchmarkTaskScore(it.result) ?: -1 }.thenBy { benchmarkTokensPerSecond(it.result) ?: 0.0 })
            val best = bestOverall?.takeIf { it.modelPath != fastest?.modelPath }
                ?: deepPool.filter { it.modelPath != fastest?.modelPath }.maxWithOrNull(compareBy<ModelBenchmark> { benchmarkTaskScore(it.result) ?: -1 }.thenBy { benchmarkTokensPerSecond(it.result) ?: 0.0 })
                ?: bestOverall
            val assignedQuick = remember(assignmentRevision, models) { vm.assignedModel(ResponseMode.QUICK) }
            val assignedDeep = remember(assignmentRevision, models) { vm.assignedModel(ResponseMode.DEEP) }
            if (fastest != null || best != null || installedBenchmarks.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Your phone's results")
                if (installedBenchmarks.any { !benchmarkHasAgentChecks(it.result) }) Text("Some results are from an older benchmark. Run again to check agent skills.", color = Muted, fontSize = 12.sp)
                fastest?.let { result ->
                    val model = models.firstOrNull { it.path == result.modelPath }
                    if (model != null) ModelRoleRow("Quick", model.name, "${benchmarkTokensPerSecond(result.result)?.let { "%.1f".format(it) } ?: "?"} tokens/s · ${benchmarkTaskScore(result.result) ?: "?"}/5 agent checks", assignedQuick == model.path) {
                        vm.assignModel(ResponseMode.QUICK, model.path); assignmentRevision++
                    }
                } ?: Text("Quick: no model cleared 4/5 checks at 8 tokens/s yet.", color = Muted, fontSize = 12.sp)
                best?.let { result ->
                    val model = models.firstOrNull { it.path == result.modelPath }
                    if (model != null) ModelRoleRow("Deep", model.name, "${benchmarkTaskScore(result.result) ?: "?"}/5 agent checks · ${benchmarkTokensPerSecond(result.result)?.let { "%.1f".format(it) } ?: "?"} tokens/s", assignedDeep == model.path) {
                        vm.assignModel(ResponseMode.DEEP, model.path); assignmentRevision++
                    }
                } ?: Text("Deep: no model cleared 4/5 checks at 3 tokens/s yet.", color = Muted, fontSize = 12.sp)
            }
            if (models.isEmpty()) {
                EmptyState("Ready when you are", "Download a recommended model or import a GGUF file to chat offline.")
                Button(onClick = { tab = 1 }) { Text("Discover models") }
            }
            models.forEach { model ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { viewed = model }.padding(vertical = 10.dp)) {
                        Text(model.name, color = Ink, fontWeight = FontWeight.Medium)
                        Text("Phone · ${sizeLabel(model.bytes)}${if (model.path == selected && home.selected == null) " · Selected" else ""}", color = Muted, fontSize = 12.sp)
                        if (!vm.library.isPhoneAgentSize(model.bytes)) Text("Outside phone agent benchmark range", color = Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { if (model.path != selected || home.selected != null) vm.setModel(model.path) else viewed = model }, enabled = benchmarking == null && !busy) { Text(if (model.path == selected && home.selected == null) "Details" else "Use") }
                }
                HorizontalDivider(color = Line)
            }
            TextButton(onClick = { adding = true }) { Text("+ Add model") }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Models people use")
                    Text("Live Hugging Face list of popular and recently updated models with real downloads. Only single-file options in this phone's practical size range appear.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
                TextButton(onClick = vm::refreshPopularModels, enabled = !popularLoading) { Text(if (popularLoading) "Loading…" else "Refresh") }
            }
            if (popularLoading && popularModels.isEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent)
            val phonePopularModels = remember(popularModels, models, download.running, vm.library) {
                popularModels.mapNotNull { popular ->
                    popular.copy(files = popular.files.filter { vm.library.isPhoneAgentDownloadSize(it.size) })
                        .takeIf { it.files.isNotEmpty() }
                }
            }
            val suitableFiles = phonePopularModels.flatMap { popular -> popular.files.map { popular to it } }
            val quickLimit = minOf(2_500_000_000L, vm.library.phoneAgentDownloadLimitBytes())
            val quickPick = suitableFiles.filter { (_, file) -> file.size <= quickLimit }
                .maxWithOrNull(compareBy<Pair<PopularGgufModel, RemoteModel>> { it.first.downloads }.thenBy { it.second.size })
                ?: suitableFiles.minByOrNull { it.second.size }
            val deepPool = suitableFiles.filter { (_, file) -> quickPick != null && file.url != quickPick.second.url && file.size >= quickPick.second.size * 6 / 5 }
            val deepPick = deepPool.maxWithOrNull(compareBy<Pair<PopularGgufModel, RemoteModel>> { it.first.downloads }.thenBy { it.second.size })
            quickPick?.let { (popular, file) ->
                RemoteRoleCard(if(models.isEmpty()) "Phone-size candidate" else "Smaller candidate", popular, file, "A smaller popular model within this phone's estimated limits. It is only a candidate until its speed and agent skills are benchmarked here.") {
                    vm.download(file.url, file.name)
                }
            }
            deepPick?.let { (popular, file) ->
                RemoteRoleCard("Larger candidate", popular, file, "A larger weight file that still fits this phone's estimate. It has not been tested on this phone yet.") {
                    vm.download(file.url, file.name)
                }
            }
            if (quickPick != null && deepPick == null) Text("No larger phone-size candidate is available in the live list. You can benchmark the smaller candidate after downloading it.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            if (popularModels.isNotEmpty()) Text("The live list is refreshed from Hub download and update activity. Downloaded models remain candidates until they pass this phone's benchmark.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            if (popularModels.isEmpty() && !popularLoading) Text("Popular models could not be loaded. Check your connection and refresh, or search Hugging Face below.", color = Muted, fontSize = 12.sp)
            if (popularModels.isNotEmpty() && phonePopularModels.isEmpty() && !popularLoading) Text("The live models found so far are too small, too large, or too demanding for this phone's agent range. Search for another phone-size option below.", color = Muted, fontSize = 12.sp)
            phonePopularModels.forEach { popular ->
                PaperRow("${if (popular.recentlyUpdated) "Recently updated · " else ""}${popular.repo}", "${countLabel(popular.downloads)} downloads · ${countLabel(popular.likes)} likes · ${popular.files.size} phone-size files", "Files") {
                    vm.openRepo(popular.repo)
                }
            }
            TextButton(onClick = { showSearch = !showSearch }) { Text(if (showSearch) "Hide Hugging Face search" else "Search Hugging Face") }
            if (showSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search models") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = { vm.searchRepos(query) }, enabled = query.isNotBlank()) { Text("Search") }
                }
                repos.forEach { repo -> TextButton(onClick = { vm.openRepo(repo) }) { Text(repo) } }
                val phoneFiles = files.filter { vm.library.isPhoneAgentDownloadSize(it.size) }
                if (files.isNotEmpty() && phoneFiles.isEmpty()) Text("No single-file model in this result fits the phone-size agent range. Try another model.", color = Muted, fontSize = 12.sp)
                phoneFiles.forEach { file -> PaperRow(file.name, "${sizeLabel(file.size)} · ${vm.library.suitability(file.size)}", if (models.any { it.source == file.url }) "Installed" else "Get") {
                    if (models.none { it.source == file.url }) vm.download(file.url, file.name)
                } }
            }
            TextButton(onClick = { adding = true }) { Text("Import a file or add a link") }
        }
        TextButton(onClick = { access = !access }) { Text(if (access) "Hide model access" else "Model access") }
        if (access) {
            Text("A Hugging Face token is only needed for gated models you have access to.", color = Muted, fontSize = 12.sp)
            OutlinedTextField(hf, { hf = it }, label = { Text("Hugging Face token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.setKey("hf", hf); hf = "" }, enabled = hf.isNotBlank()) { Text("Save token") }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, containerColor = Paper, title = { Text("Add a model") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")); adding = false }, modifier = Modifier.fillMaxWidth()) { Text("Import a GGUF file") }
            Text("Or download from a direct link", color = Muted, fontSize = 12.sp)
            OutlinedTextField(directUrl, { directUrl = it }, label = { Text("HTTPS GGUF URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(directName, { directName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { vm.download(directUrl, directName); adding = false }, enabled = directUrl.startsWith("https://") && directName.isNotBlank()) { Text("Download") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    viewed?.let { model ->
        val benchmark = benchmarks.firstOrNull { it.modelPath == model.path }
        AlertDialog(onDismissRequest = { viewed = null }, containerColor = Paper, title = { Text(model.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(sizeLabel(model.bytes), color = Muted)
                Text("Five checks cover planning, instruction handling, arithmetic, text judgment, and tool-call formatting. A recommendation needs 4/5 checks; Quick also needs 8+ tokens/s and Deep 3+.", color = Muted, fontSize = 12.sp)
                if (!vm.library.isPhoneAgentSize(model.bytes)) Text("This file is outside the phone-size range used for agent recommendations.", color = Muted, fontSize = 12.sp)
                if (benchmark != null) {
                    val count = benchmarkCheckCount(benchmark.result)
                    Text("${benchmarkTokensPerSecond(benchmark.result)?.let { "%.1f".format(it) } ?: "?"} tokens/s · ${benchmarkTaskScore(benchmark.result) ?: "?"}/${count ?: "?"} ${if (benchmarkHasAgentChecks(benchmark.result)) "agent checks" else "older checks"}", color = Accent)
                    Text(benchmark.result, color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { vm.runBenchmark(model) }, enabled = vm.library.isPhoneAgentSize(model.bytes) && benchmarking == null && !busy && !download.running) { Text(if (benchmarking == model.path) "Testing…" else "Benchmark") }
                TextButton(onClick = { deleting = model; viewed = null }, enabled = model.path != selected && benchmarking == null && !busy) { Text("Remove model") }
                if (model.path == selected) Text("Select another model before removing this one.", color = Muted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { vm.setModel(model.path); viewed = null }, enabled = benchmarking == null && !busy) { Text(if (model.path == selected) "Done" else "Use model") } })
    }
    deleting?.let { model -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${model.name}?") },
        confirmButton = { TextButton(onClick = { vm.removeModel(model); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun ModelRoleRow(role: String, name: String, detail: String, assigned: Boolean, onAssign: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$role · $name", color = Ink, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
        TextButton(onClick = onAssign, enabled = !assigned) { Text(if (assigned) "In use" else "Use") }
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun RemoteRoleCard(role: String, popular: PopularGgufModel, model: RemoteModel, detail: String, onGet: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(role, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(popular.repo, color = Ink, fontWeight = FontWeight.Medium)
        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("${sizeLabel(model.size)} · ${model.file.substringAfterLast('/')} · ${countLabel(popular.downloads)} downloads", color = Muted, fontSize = 11.sp)
        Button(onClick = onGet) { Text("Download to benchmark") }
    }
}

private fun benchmarkTokensPerSecond(result: String): Double? = Regex("\\| tg 64 \\| ([0-9.]+)").find(result)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
private fun benchmarkHasAgentChecks(result: String): Boolean = Regex("Cina agent checks: [0-5]/5").containsMatchIn(result)
private fun benchmarkCheckCount(result: String): Int? = when {
    benchmarkHasAgentChecks(result) -> 5
    Regex("Cina task checks: [0-3]/3").containsMatchIn(result) -> 3
    else -> null
}
private fun benchmarkTaskScore(result: String): Int? = Regex("Cina agent checks: ([0-5])/5|Cina task checks: ([0-3])/3")
    .find(result)?.let { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) ?: it.groupValues.getOrNull(2) }?.toIntOrNull()

private fun countLabel(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}


internal fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
