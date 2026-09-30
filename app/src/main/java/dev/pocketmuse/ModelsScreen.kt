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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ModelsScreen(vm: AssistantViewModel) {
    val models by vm.models.collectAsState(); val selected by vm.selectedModel.collectAsState()
    val download by vm.download.collectAsState(); val benchmarks by vm.benchmarks.collectAsState()
    val benchmarking by vm.benchmarking.collectAsState(); val busy by vm.busy.collectAsState()
    val resumable by vm.resumable.collectAsState(); val repos by vm.repoResults.collectAsState(); val files by vm.remoteFiles.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(if (models.isEmpty()) 1 else 0) }
    var query by rememberSaveable { mutableStateOf("") }
    var directUrl by rememberSaveable { mutableStateOf("") }; var directName by rememberSaveable { mutableStateOf("") }
    var hf by remember { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var access by rememberSaveable { mutableStateOf(false) }
    var viewed by remember { mutableStateOf<LocalModel?>(null) }
    var deleting by remember { mutableStateOf<LocalModel?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    Page("Models", "Choose a mind for Cina. It runs on your phone.") {
        ScreenTabs(listOf("Installed", "Discover"), tab) { tab = it }
        Text("${sizeLabel(vm.library.freeBytes())} free · ${sizeLabel(vm.library.totalRam())} memory", color = Muted, fontSize = 12.sp)
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
            val fastest = installedBenchmarks.maxByOrNull { benchmarkTokensPerSecond(it.result) ?: -1.0 }
            val best = installedBenchmarks.maxWithOrNull(compareBy<ModelBenchmark> { benchmarkTaskScore(it.result) ?: -1 }.thenBy { benchmarkTokensPerSecond(it.result) ?: 0.0 })
            if (fastest != null || best != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                fastest?.let { result -> Text("Fastest tested: ${models.first { it.path == result.modelPath }.name}", color = Accent, fontSize = 12.sp) }
                best?.let { result -> Text("Best for Cina checks: ${models.first { it.path == result.modelPath }.name} · ${benchmarkTaskScore(result.result) ?: 0}/3", color = Muted, fontSize = 12.sp) }
            }
            if (models.isEmpty()) {
                EmptyState("Ready when you are", "Download a recommended model or import a GGUF file to chat offline.")
                Button(onClick = { tab = 1 }) { Text("Discover models") }
            }
            models.forEach { model ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { viewed = model }.padding(vertical = 10.dp)) {
                        Text(model.name, color = Ink, fontWeight = FontWeight.Medium)
                        Text("${sizeLabel(model.bytes)}${if (model.path == selected) " · Selected" else ""}", color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { if (model.path != selected) vm.setModel(model.path) else viewed = model }) { Text(if (model.path == selected) "Details" else "Use") }
                }
                HorizontalDivider(color = Line)
            }
            TextButton(onClick = { adding = true }) { Text("+ Add model") }
        } else {
            SectionTitle("Recommended for your phone")
            CuratedModels.entries.forEach { remote ->
                PaperRow(remote.name, vm.library.suitability(remote.size), if (models.any { it.source == remote.url }) "Installed" else "Get") {
                    if (models.none { it.source == remote.url }) vm.download(remote.url, remote.name)
                }
            }
            TextButton(onClick = { showSearch = !showSearch }) { Text(if (showSearch) "Hide Hugging Face search" else "Search Hugging Face") }
            if (showSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search models") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = { vm.searchRepos(query) }, enabled = query.isNotBlank()) { Text("Search") }
                }
                repos.forEach { repo -> TextButton(onClick = { vm.openRepo(repo) }) { Text(repo) } }
                files.forEach { file -> PaperRow(file.name, "${sizeLabel(file.size)} · ${vm.library.suitability(file.size)}", "Get") { vm.download(file.url, file.name) } }
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
                Text("Test speed and three basic tasks on this phone. These checks are a guide, not a full quality rating.", color = Muted, fontSize = 12.sp)
                if (benchmark != null) {
                    Text("${benchmarkTokensPerSecond(benchmark.result)?.let { "%.1f".format(it) } ?: "?"} tokens/s · ${benchmarkTaskScore(benchmark.result) ?: "?"}/3 checks", color = Accent)
                    Text(benchmark.result, color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { vm.runBenchmark(model) }, enabled = benchmarking == null && !busy) { Text(if (benchmarking == model.path) "Testing…" else "Benchmark") }
                TextButton(onClick = { deleting = model; viewed = null }, enabled = model.path != selected) { Text("Remove model") }
                if (model.path == selected) Text("Select another model before removing this one.", color = Muted, fontSize = 12.sp)
            } },
            confirmButton = { TextButton(onClick = { vm.setModel(model.path); viewed = null }) { Text(if (model.path == selected) "Done" else "Use model") } })
    }
    deleting?.let { model -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove ${model.name}?") },
        confirmButton = { TextButton(onClick = { vm.removeModel(model); deleting = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

private fun benchmarkTokensPerSecond(result: String): Double? = Regex("\\| tg 64 \\| ([0-9.]+)").find(result)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
private fun benchmarkTaskScore(result: String): Int? = Regex("Cina task checks: ([0-3])/3").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull()


internal fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
