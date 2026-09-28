package dev.pocketmuse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Paper = Color(0xFFFAF9F6)
private val Ink = Color(0xFF26312C)
private val Muted = Color(0xFF767E78)
private val Line = Color(0xFFE7E9E2)
private val Soft = Color(0xFFF0F2EC)
private val Accent = Color(0xFF4F7562)
private val Warm = Color(0xFFF4F0E9)

class MainActivity : ComponentActivity() {
    private val vm: AssistantViewModel by viewModels()
    private var availableUpdate by mutableStateOf<AppUpdate?>(null)
    private var updateStatus by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.attach(this)
        window.statusBarColor = android.graphics.Color.rgb(250, 249, 246)
        window.navigationBarColor = android.graphics.Color.rgb(250, 249, 246)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 23)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Accent, onPrimary = Color.White, background = Paper, onBackground = Ink,
                    surface = Paper, onSurface = Ink, surfaceVariant = Soft, onSurfaceVariant = Muted, outline = Line,
                    secondaryContainer = Soft, onSecondaryContainer = Ink, error = Color(0xFF9B4D47)),
                shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp))
            ) {
                CinaApp(vm, availableUpdate, updateStatus,
                    onCheckUpdates = { checkForUpdates(showResult = true) },
                    onDismissUpdate = { availableUpdate = null },
                    onDownloadUpdate = { update ->
                        startActivity(Intent(Intent.ACTION_VIEW, update.apkUrl))
                        availableUpdate = null
                    })
            }
        }
        checkForUpdates(showResult = false)
    }
    override fun onStop() { vm.endSession(); super.onStop() }

    private fun checkForUpdates(showResult: Boolean) {
        if (BuildConfig.DEBUG) {
            if (showResult) updateStatus = "Install a signed release APK to receive updates."
            return
        }
        if (showResult) updateStatus = "Checking for updates…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.latestAvailable(BuildConfig.VERSION_CODE) }
            }
            result.onSuccess { update ->
                availableUpdate = update
                if (showResult) updateStatus = if (update == null) "You're up to date." else "A new build is available."
            }.onFailure {
                if (showResult) updateStatus = "Could not check for updates. Try again later."
            }
        }
    }
}

@Composable
private fun CinaApp(vm: AssistantViewModel, availableUpdate: AppUpdate?, updateStatus: String,
    onCheckUpdates: () -> Unit, onDismissUpdate: () -> Unit, onDownloadUpdate: (AppUpdate) -> Unit) {
    var page by remember { mutableStateOf("Chat") }
    var menuOpen by remember { mutableStateOf(false) }
    val status by vm.status.collectAsState()
    val pending by vm.pending.collectAsState()
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if(page == "Chat") {
                    RoundControl("☰", "Open menu") { menuOpen = true }
                    Spacer(Modifier.width(14.dp))
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 30.sp, color = Ink)
                } else {
                    RoundControl("‹", "Back to chat") { page = "Chat" }
                    Spacer(Modifier.width(14.dp))
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 27.sp, color = Ink)
                }
                Spacer(Modifier.weight(1f))
                Text("ON YOUR DEVICE", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, color = Muted)
            }
            AnimatedVisibility(status.isNotBlank(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, Modifier.weight(1f).padding(vertical = 10.dp), color = Ink, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearStatus) { Text("Close") }
                }
            }
            AnimatedContent(targetState = page, modifier = Modifier.weight(1f), transitionSpec = {
                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 10 }) togetherWith fadeOut(tween(110))
            }, label = "Cina pages") { destination ->
                when(destination) {
                    "Chat" -> ChatScreen(vm, onModels = { page = "Models" })
                    "Models" -> ModelsScreen(vm)
                    "Notes" -> NotesScreen(vm)
                    "Memory" -> MemoryScreen(vm)
                    else -> SettingsScreen(vm, updateStatus, onCheckUpdates)
                }
            }
        }
        AnimatedVisibility(menuOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(130))) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .18f)).clickable { menuOpen = false })
        }
        AnimatedVisibility(menuOpen, enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(180))) {
            Surface(Modifier.fillMaxHeight().fillMaxWidth(.79f), color = Paper, shadowElevation = 10.dp) {
                Column(Modifier.padding(26.dp)) {
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 38.sp, color = Ink)
                    Text("A little space to think.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(42.dp))
                    listOf("Chat", "Models", "Notes", "Memory", "Settings").forEach { destination ->
                        Row(Modifier.fillMaxWidth().clickable { page = destination; menuOpen = false }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(destination, Modifier.weight(1f), fontSize = 19.sp, color = if(page == destination) Accent else Ink,
                                fontWeight = if(page == destination) FontWeight.SemiBold else FontWeight.Normal)
                            if(page == destination) Text("•", color = Accent)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Private by design. Here with you.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(pending != null) AlertDialog(onDismissRequest = vm::reject, containerColor = Paper,
        title = { Text("Let Cina do this?") }, text = { Text("${pending!!.request.name}\n${pending!!.request.arguments}") },
        confirmButton = { TextButton(onClick = vm::approve) { Text("Allow") } },
        dismissButton = { TextButton(onClick = vm::reject) { Text("Not now") } })
    if (availableUpdate != null && pending == null) AlertDialog(onDismissRequest = onDismissUpdate, containerColor = Paper,
        title = { Text("Cina update available") },
        text = { Text("${availableUpdate.version} is ready. Download the APK to update Cina.") },
        confirmButton = { TextButton(onClick = { onDownloadUpdate(availableUpdate) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismissUpdate) { Text("Later") } })
}

@Composable
private fun RoundControl(symbol: String, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Soft, modifier = Modifier.size(40.dp).clickable(onClickLabel = description, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 23.sp, color = Ink) }
    }
}

@Composable
private fun ChatScreen(vm: AssistantViewModel, onModels: () -> Unit) {
    val chats by vm.chats.collectAsState(); val active by vm.active.collectAsState(); val messages by vm.messages.collectAsState()
    val live by vm.live.collectAsState(); val busy by vm.busy.collectAsState(); val logs by vm.logs.collectAsState()
    val selected by vm.selectedModel.collectAsState(); val models by vm.models.collectAsState()
    val chat = chats.firstOrNull { it.id == active }
    val modelName = models.firstOrNull { it.path == selected }?.name
    var text by remember { mutableStateOf("") }
    var showChats by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, busy) { if(messages.isNotEmpty() || busy) listState.animateScrollToItem(if(busy) messages.size else (messages.size - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showChats = true }, contentPadding = PaddingValues(0.dp)) {
                Text(chat?.title ?: "Conversation", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp)); Text("⌄", color = Muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::newChat) { Text("New chat", color = Accent) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill(modelName ?: "Choose a model", active = modelName != null, onClick = onModels)
            SmallPill("Web ${if(chat?.web == true) "on" else "off"}", active = chat?.web == true) { vm.setFlag("web", chat?.web != true) }
            SmallPill("YOLO ${if(chat?.yolo == true) "on" else "off"}", active = chat?.yolo == true, prominent = true) { vm.setFlag("yolo", chat?.yolo != true) }
            SmallPill("Actions", active = false) { showLog = true }
        }
        AnimatedVisibility(chat?.yolo == true, enter = fadeIn(tween(180)), exit = fadeOut(tween(150))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).background(Color(0xFFEAF1E9), RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 8.dp, top = 3.dp, bottom = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Actions run without asking", Modifier.weight(1f), color = Accent, fontSize = 12.sp)
                    TextButton(onClick = { vm.stop(); vm.setFlag("yolo", false) }) { Text("Stop") }
                }
                Text(if(logs.isEmpty()) "No actions yet." else logs.take(2).joinToString("\n"), color = Muted, fontSize = 11.sp, lineHeight = 17.sp, maxLines = 3)
            }
        }
        if(messages.isEmpty() && !busy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Hello, I'm Cina.", fontFamily = FontFamily.Serif, fontSize = 33.sp, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    Text(if(modelName == null) "Choose a model to begin." else "What's on your mind?", color = Muted, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(messages, key = { it.id }) { message ->
                    if(message.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = Warm, shape = RoundedCornerShape(22.dp, 22.dp, 5.dp, 22.dp)) {
                                Text(message.body, Modifier.widthIn(max = 310.dp).padding(horizontal = 17.dp, vertical = 13.dp), color = Ink, fontSize = 15.sp, lineHeight = 23.sp)
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().animateContentSize()) {
                            Text("CINA", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Text(message.body, color = Ink, fontSize = 16.sp, lineHeight = 25.sp)
                        }
                    }
                }
                if(busy) item {
                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                        Text("CINA", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if(live.isBlank()) WorkingIndicator() else Text(live, color = Ink, fontSize = 16.sp, lineHeight = 25.sp)
                    }
                }
            }
        }
        Surface(color = Paper, shadowElevation = 7.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 15.dp)
                .background(Color.White, RoundedCornerShape(25.dp)).padding(start = 18.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom) {
                BasicTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 5,
                    decorationBox = { inner -> Box { if(text.isBlank()) Text("Ask Cina anything...", color = Muted, fontSize = 16.sp); inner() } })
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if(busy) vm.stop() else { vm.send(text); text = "" } }, enabled = busy || text.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.White), modifier = Modifier.size(42.dp)) {
                    Text(if(busy) "■" else "↑", fontSize = if(busy) 16.sp else 23.sp)
                }
            }
        }
    }
    if(showChats) AlertDialog(onDismissRequest = { showChats = false }, containerColor = Paper, title = { Text("Conversations") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { chats.forEach { item -> TextButton(onClick = { vm.openChat(item.id); showChats = false }) { Text(item.title, color = Ink) } } } },
        confirmButton = { TextButton(onClick = { showChats = false }) { Text("Done") } })
    if(showLog) AlertDialog(onDismissRequest = { showLog = false }, containerColor = Paper, title = { Text("Recent actions") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { if(logs.isEmpty()) Text("Nothing yet.", color = Muted) else logs.forEach { Text(it, Modifier.padding(bottom = 14.dp), color = Ink) } } },
        confirmButton = { TextButton(onClick = { showLog = false }) { Text("Done") } })
}

@Composable
private fun WorkingIndicator() {
    val transition = rememberInfiniteTransition(label = "Cina thinking")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(initialValue = .25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 140), RepeatMode.Reverse), label = "thinking dot $index")
            Box(Modifier.size(6.dp).background(Accent.copy(alpha = alpha), CircleShape))
        }
        Spacer(Modifier.width(7.dp)); Text("Thinking", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SmallPill(label: String, active: Boolean, prominent: Boolean = false, onClick: () -> Unit) {
    val color = if(active) { if(prominent) Accent else Soft } else if(prominent) Color(0xFFEAF1E9) else Color.Transparent
    Surface(shape = CircleShape, color = color, border = if(active || prominent) null else androidx.compose.foundation.BorderStroke(1.dp, Line),
        modifier = Modifier.clickable(onClick = onClick)) {
        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 12.sp,
            color = if(active && prominent) Color.White else if(prominent) Accent else if(active) Ink else Muted)
    }
}

@Composable
private fun Page(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 31.sp, color = Ink)
        if(subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink) }

@Composable
private fun PaperRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp) }
        Spacer(Modifier.width(8.dp)); TextButton(onClick = onClick) { Text(action) }
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun ModelsScreen(vm: AssistantViewModel) {
    val models by vm.models.collectAsState(); val selected by vm.selectedModel.collectAsState(); val download by vm.download.collectAsState()
    val resumable by vm.resumable.collectAsState(); val repos by vm.repoResults.collectAsState(); val files by vm.remoteFiles.collectAsState()
    var query by remember { mutableStateOf("") }; var directUrl by remember { mutableStateOf("") }; var directName by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.import(uri) }
    Page("Models", "Choose a mind for Cina. Models live and run on your phone.") {
        Text("${sizeLabel(vm.library.freeBytes())} free space  ·  ${sizeLabel(vm.library.totalRam())} phone memory", color = Muted, fontSize = 12.sp)
        if(download.running) {
            Text("Downloading ${download.name}", color = Ink)
            LinearProgressIndicator(progress = { (download.received.toFloat() / download.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Accent)
            Text("${sizeLabel(download.received)} of ${sizeLabel(download.total)}", color = Muted, fontSize = 12.sp)
            TextButton(onClick = vm::cancelDownload) { Text("Pause download") }
        }
        if(download.error.isNotBlank()) Text(download.error, color = MaterialTheme.colorScheme.error)
        if(resumable != null && !download.running) Button(onClick = { vm.download(resumable!!.first, resumable!!.second) }) { Text("Resume ${resumable!!.second}") }
        SectionTitle("Recommended")
        CuratedModels.entries.forEach { remote -> PaperRow(remote.name, "${remote.repo} · ${vm.library.suitability(remote.size)}", "Get") { vm.download(remote.url, remote.name) } }
        Spacer(Modifier.height(6.dp)); SectionTitle("On this phone")
        if(models.isEmpty()) Text("No models yet.", color = Muted)
        models.forEach { model ->
            PaperRow(model.name, sizeLabel(model.bytes), if(model.path == selected) "Selected" else "Use") { if(model.path != selected) vm.setModel(model.path) }
            TextButton(onClick = { vm.removeModel(model) }, enabled = model.path != selected) { Text("Remove", color = Muted, fontSize = 12.sp) }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import a GGUF file") }
        Spacer(Modifier.height(6.dp)); SectionTitle("Find on Hugging Face")
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(query, { query = it }, label = { Text("Search models") }, modifier = Modifier.weight(1f), singleLine = true); TextButton(onClick = { vm.searchRepos(query) }) { Text("Search") } }
        repos.forEach { repo -> TextButton(onClick = { vm.openRepo(repo) }) { Text(repo) } }
        files.forEach { file -> PaperRow(file.name, "${sizeLabel(file.size)} · ${vm.library.suitability(file.size)}", "Get") { vm.download(file.url, file.name) } }
        Spacer(Modifier.height(6.dp)); SectionTitle("Add a model link")
        OutlinedTextField(directUrl, { directUrl = it }, label = { Text("HTTPS GGUF URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(directName, { directName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.download(directUrl, directName) }, enabled = directUrl.startsWith("https://") && directName.isNotBlank()) { Text("Download") }
    }
}

@Composable
private fun NotesScreen(vm: AssistantViewModel) {
    val notes by vm.notes.collectAsState(); val reminders by vm.reminders.collectAsState()
    var title by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }; var adding by remember { mutableStateOf(false) }
    Page("Notes & reminders", "Small things worth keeping close.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { SectionTitle("Notes"); Spacer(Modifier.weight(1f)); TextButton(onClick = { adding = !adding }) { Text(if(adding) "Close" else "+ Add note") } }
        AnimatedVisibility(adding) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("Your note") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = { vm.addNote(title, body); title = ""; body = ""; adding = false }, enabled = title.isNotBlank() && body.isNotBlank()) { Text("Save note") }
            }
        }
        if(notes.isEmpty()) Text("Your notes will appear here.", color = Muted)
        notes.forEach { note -> PaperRow(note.title, note.body, "Delete") { vm.deleteNote(note.id) } }
        Spacer(Modifier.height(8.dp)); SectionTitle("Reminders")
        Text("Ask Cina to set a reminder with a date and time.", color = Muted, fontSize = 13.sp)
        val open = reminders.filter { !it.done }
        if(open.isEmpty()) Text("No upcoming reminders.", color = Muted)
        open.forEach { reminder -> PaperRow(reminder.title, DateFormat.getDateTimeInstance().format(Date(reminder.whenMillis)), "Done") { vm.completeReminder(reminder.id) } }
    }
}

@Composable
private fun MemoryScreen(vm: AssistantViewModel) {
    val memories by vm.memories.collectAsState(); val enabled by vm.memoryEnabled.collectAsState()
    var editing by remember { mutableStateOf<Memory?>(null) }; var draft by remember { mutableStateOf("") }
    Page("Memory", "You decide what Cina remembers. Everything stays on this phone.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Remember useful details", Modifier.weight(1f), color = Ink); Switch(enabled, onCheckedChange = vm::setMemoryEnabled) }
        HorizontalDivider(color = Line)
        if(memories.isEmpty()) Text("Nothing saved yet.", color = Muted)
        memories.forEach { memory ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(memory.fact, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
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

@Composable
private fun SettingsScreen(vm: AssistantViewModel, updateStatus: String, onCheckUpdates: () -> Unit) {
    var hf by remember { mutableStateOf("") }; var brave by remember { mutableStateOf("") }
    Page("Settings", "Just the essentials. Cina's conversations and personal data stay on your phone.") {
        SectionTitle("App updates")
        Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 13.sp)
        TextButton(onClick = onCheckUpdates) { Text("Check for updates") }
        if (updateStatus.isNotBlank()) Text(updateStatus, color = Muted, fontSize = 13.sp)
        HorizontalDivider(color = Line); Spacer(Modifier.height(4.dp))
        SectionTitle("Model access")
        Text("A Hugging Face token is only needed for gated models you have access to.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(hf, { hf = it }, label = { Text("Hugging Face token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setKey("hf", hf); hf = "" }, enabled = hf.isNotBlank()) { Text("Save token") }
        HorizontalDivider(color = Line); Spacer(Modifier.height(4.dp))
        SectionTitle("Optional web search")
        Text("When enabled in a chat, only the search query goes to Brave.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(brave, { brave = it }, label = { Text("Brave Search API key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setKey("brave", brave); brave = "" }, enabled = brave.isNotBlank()) { Text("Save key") }
        Text("Keys are encrypted and stored on this device.", color = Muted, fontSize = 12.sp)
    }
}

private fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
