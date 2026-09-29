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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    companion object {
        const val EXTRA_WIDGET_PAGE = "widget_page"
        const val EXTRA_WIDGET_PROMPT = "widget_prompt"
    }
    private val vm: AssistantViewModel by viewModels()
    private var scheduledTaskOpenRequest by mutableIntStateOf(0)
    private var widgetPageRequest by mutableStateOf<Pair<Int, String>?>(null)
    private var widgetRequestId = 0
    private var widgetDraft by mutableStateOf<String?>(null)
    private var widgetDraftKey by mutableIntStateOf(0)
    private var availableUpdate by mutableStateOf<AppUpdate?>(null)
    private var updateStatus by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_scheduled_tasks", false) == true) scheduledTaskOpenRequest++
        vm.attach(this)
        handleWidgetIntent(intent)
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
                CinaApp(vm, availableUpdate, updateStatus, scheduledTaskOpenRequest,
                    widgetPageRequest, widgetDraft, widgetDraftKey,
                    onCheckUpdates = { checkForUpdates(showResult = true) },
                    onDismissUpdate = { availableUpdate = null },
                    onDownloadUpdate = { update ->
                        startActivity(Intent(Intent.ACTION_VIEW, update.apkUrl))
                        availableUpdate = null
                    })
            }
        }
        intent?.data?.takeIf { it.scheme == "dev.pocketmuse.cina" }?.let(vm::finishLinearSignIn)
        checkForUpdates(showResult = false)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_scheduled_tasks", false)) scheduledTaskOpenRequest++
        handleWidgetIntent(intent)
        intent.data?.takeIf { it.scheme == "dev.pocketmuse.cina" }?.let(vm::finishLinearSignIn)
    }
    override fun onResume() { super.onResume(); vm.refresh(); TodayWidget.updateAll(this) }
    override fun onStop() { vm.endSession(); super.onStop() }

    private fun handleWidgetIntent(intent: Intent?) {
        val prompt = intent?.getStringExtra(EXTRA_WIDGET_PROMPT)?.trim()?.takeIf { it.isNotEmpty() }
        if (prompt != null) {
            vm.newChat()
            widgetDraft = if (vm.models.value.any { it.path == vm.selectedModel.value }) {
                vm.send(prompt); null
            } else prompt
            widgetDraftKey++
            widgetPageRequest = ++widgetRequestId to "Chat"
            return
        }
        intent?.getStringExtra(EXTRA_WIDGET_PAGE)?.let { page ->
            if (page in setOf("Notes", "Scheduled tasks", "Chat")) widgetPageRequest = ++widgetRequestId to page
        }
    }

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
private fun CinaApp(vm: AssistantViewModel, availableUpdate: AppUpdate?, updateStatus: String, scheduledTaskOpenRequest: Int,
    widgetPageRequest: Pair<Int, String>?, widgetDraft: String?, widgetDraftKey: Int,
    onCheckUpdates: () -> Unit, onDismissUpdate: () -> Unit, onDownloadUpdate: (AppUpdate) -> Unit) {
    var page by remember { mutableStateOf("Chat") }
    LaunchedEffect(scheduledTaskOpenRequest) { if (scheduledTaskOpenRequest > 0) page = "Scheduled tasks" }
    LaunchedEffect(widgetPageRequest?.first) { widgetPageRequest?.let { page = it.second } }
    var menuOpen by remember { mutableStateOf(false) }
    val status by vm.status.collectAsState()
    val pending by vm.pending.collectAsState()
    val youProfile by vm.youProfile.collectAsState()
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
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
                if (youProfile.showPet) {
                    CompanionBubble(youProfile, 48.dp, Modifier.clickable(onClickLabel = "Customize Cina") { page = "Settings" })
                } else {
                    Text("ON YOUR DEVICE", fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, color = Muted)
                }
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
                    "Chat" -> ChatScreen(vm, onModels = { page = "Models" }, widgetDraft = widgetDraft, widgetDraftKey = widgetDraftKey)
                    "Models" -> ModelsScreen(vm)
                    "Notes" -> NotesScreen(vm)
                    "Tasks" -> AgentTasksScreen(vm, onOpenChat = { page = "Chat" })
                    "Scheduled tasks" -> ScheduledTasksScreen(vm)
                    "Memory" -> MemoryScreen(vm, onOpenChat = { page = "Chat" })
                    "You" -> YouScreen(youProfile, vm::saveYouProfile)
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
                    listOf("Chat", "Tasks", "Models", "Notes", "Scheduled tasks", "Memory", "Settings").forEach { destination ->
                        Row(Modifier.fillMaxWidth().clickable { page = destination; menuOpen = false }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(destination, Modifier.weight(1f), fontSize = 19.sp, color = if(page == destination) Accent else Ink,
                                fontWeight = if(page == destination) FontWeight.SemiBold else FontWeight.Normal)
                            if(page == destination) Text("•", color = Accent)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().clickable { page = "You"; menuOpen = false }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("You", Modifier.weight(1f), fontSize = 19.sp, color = if(page == "You") Accent else Ink,
                            fontWeight = if(page == "You") FontWeight.SemiBold else FontWeight.Normal)
                        if(page == "You") Text("•", color = Accent)
                    }
                    Text("Private by design. Here with you.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(pending != null) AlertDialog(onDismissRequest = vm::reject, containerColor = Paper,
        title = { Text("Let Cina do this?") }, text = { Text("${pending!!.request.name}\n${pending!!.request.arguments}") },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.approve(always = true) }) { Text("Always allow") }
                TextButton(onClick = { vm.approve() }) { Text("Allow") }
            }
        },
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
private fun ChatScreen(vm: AssistantViewModel, onModels: () -> Unit, widgetDraft: String?, widgetDraftKey: Int) {
    val chats by vm.chats.collectAsState(); val active by vm.active.collectAsState(); val messages by vm.messages.collectAsState()
    val live by vm.live.collectAsState(); val busy by vm.busy.collectAsState(); val logs by vm.logs.collectAsState()
    val selected by vm.selectedModel.collectAsState(); val models by vm.models.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) vm.attachFile(uri) }
    val chat = chats.firstOrNull { it.id == active }
    val modelName = models.firstOrNull { it.path == selected }?.name
    var text by remember { mutableStateOf("") }
    LaunchedEffect(widgetDraftKey) { if (widgetDraftKey > 0) text = widgetDraft.orEmpty() }
    var showChats by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, busy) { if(messages.isNotEmpty() || busy) listState.animateScrollToItem(if(busy) messages.size else (messages.size - 1).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showChats = true }, contentPadding = PaddingValues(0.dp)) {
                Text(chat?.title ?: "Conversation", modifier = Modifier.widthIn(max = 190.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp)); Text("⌄", color = Muted)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::newChat) { Text("New chat", color = Accent) }
            Box {
                RoundControl("⋯", "Chat options") { showOptions = true }
                DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }, containerColor = Paper) {
                    DropdownMenuItem(text = { Text("Model · ${modelName ?: "Choose one"}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { showOptions = false; onModels() })
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(text = { Text("Web search") }, trailingIcon = { Switch(checked = chat?.web == true, onCheckedChange = { vm.setFlag("web", it); showOptions = false }) },
                        onClick = { vm.setFlag("web", chat?.web != true); showOptions = false })
                    DropdownMenuItem(text = { Text("YOLO mode") }, trailingIcon = { Switch(checked = chat?.yolo == true, onCheckedChange = { vm.setFlag("yolo", it); showOptions = false }) },
                        onClick = { vm.setFlag("yolo", chat?.yolo != true); showOptions = false })
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(text = { Text("Recent actions") }, onClick = { showOptions = false; showLog = true })
                }
            }
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
                            AssistantResponse(message.body)
                        }
                    }
                }
                if(busy) item {
                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                        Text("CINA", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if(live.isBlank()) WorkingIndicator() else AssistantResponse(live, streaming = true)
                    }
                }
            }
        }
        Surface(color = Paper, shadowElevation = 7.dp) {
            Column {
            if (attachments.isNotEmpty()) Text("Files in this chat: ${attachments.take(3).joinToString { it.name }}", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), color = Muted, fontSize = 12.sp, maxLines = 2)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 15.dp)
                .background(Color.White, RoundedCornerShape(25.dp)).padding(start = 18.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom) {
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("＋", color = Accent, fontSize = 22.sp) }
                BasicTextField(value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 5,
                    decorationBox = { inner -> Box { if(text.isBlank()) Text("Ask Cina anything...", color = Muted, fontSize = 16.sp); inner() } })
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if(busy) vm.stop() else { vm.send(text); text = "" } }, enabled = busy || text.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.White), modifier = Modifier.size(42.dp)) {
                    Text(if(busy) "■" else "↑", fontSize = if(busy) 16.sp else 23.sp)
                }
            }
            if (text.isNotBlank() && !busy) TextButton(onClick = { vm.startGoal(text); text = "" }, modifier = Modifier.align(Alignment.End).padding(end = 18.dp)) { Text("Start as goal") }
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
private fun AssistantResponse(raw: String, streaming: Boolean = false) {
    val response = remember(raw) { splitModelResponse(raw) }
    var showThinking by remember { mutableStateOf(false) }
    if (response.thinking.isNotBlank()) {
        TextButton(onClick = { showThinking = !showThinking }, contentPadding = PaddingValues(0.dp)) {
            Text(if(showThinking) "Hide thinking" else "View thinking", color = Muted, fontSize = 12.sp)
        }
        AnimatedVisibility(showThinking) {
            Text(response.thinking, Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(12.dp)).padding(12.dp),
                color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
    if (response.answer.isNotBlank()) Text(response.answer, color = Ink, fontSize = 16.sp, lineHeight = 25.sp)
    else if (streaming) WorkingIndicator()
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
    val benchmarks by vm.benchmarks.collectAsState(); val benchmarking by vm.benchmarking.collectAsState()
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
        Text("Test installed models on this phone for speed and three basic Cina tasks. These checks are a guide, not a full quality rating.", color = Muted, fontSize = 12.sp)
        val fastest = benchmarks.mapNotNull { result -> benchmarkTokensPerSecond(result.result)?.let { result.modelPath to it } }.maxByOrNull { it.second }
        if(fastest != null) Text("Fastest tested: ${models.firstOrNull { it.path == fastest.first }?.name ?: "Model"} · ${"%.1f".format(fastest.second)} tokens/s", color = Accent, fontSize = 13.sp)
        val bestForCina = benchmarks.filter { result -> models.any { it.path == result.modelPath } }.maxWithOrNull(compareBy<ModelBenchmark> { benchmarkTaskScore(it.result) ?: -1 }.thenBy { benchmarkTokensPerSecond(it.result) ?: 0.0 })
        if(bestForCina != null) Text("Best tested for Cina tasks: ${models.firstOrNull { it.path == bestForCina.modelPath }?.name ?: "Model"} · ${benchmarkTaskScore(bestForCina.result) ?: 0}/3 checks", color = Accent, fontSize = 13.sp)
        if(models.isEmpty()) Text("No models yet.", color = Muted)
        models.forEach { model ->
            PaperRow(model.name, sizeLabel(model.bytes), if(model.path == selected) "Selected" else "Use") { if(model.path != selected) vm.setModel(model.path) }
            val benchmark = benchmarks.firstOrNull { it.modelPath == model.path }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.runBenchmark(model) }, enabled = benchmarking == null) { Text(if(benchmarking == model.path) "Testing…" else "Benchmark") }
                if(benchmark != null) Text("${benchmarkTokensPerSecond(benchmark.result)?.let { "%.1f".format(it) } ?: "?"} tokens/s · ${benchmarkTaskScore(benchmark.result) ?: "?"}/3 checks", color = Muted, fontSize = 12.sp)
            }
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

private fun benchmarkTokensPerSecond(result: String): Double? = Regex("\\| tg 64 \\| ([0-9.]+)").find(result)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
private fun benchmarkTaskScore(result: String): Int? = Regex("Cina task checks: ([0-3])/3").find(result)?.groupValues?.getOrNull(1)?.toIntOrNull()

@Composable
private fun NotesScreen(vm: AssistantViewModel) {
    val notes by vm.notes.collectAsState(); val reminders by vm.reminders.collectAsState()
    var title by remember { mutableStateOf("") }; var body by remember { mutableStateOf("") }; var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Note?>(null) }
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
        notes.forEach { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(note.title, fontWeight = FontWeight.SemiBold, color = Ink); Text(note.body, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                TextButton(onClick = { editing = note; title = note.title; body = note.body }) { Text("Edit") }
                TextButton(onClick = { vm.deleteNote(note.id) }) { Text("Delete", color = Muted) }
            }
        }
        Spacer(Modifier.height(8.dp)); SectionTitle("Reminders")
        Text("Ask Cina to set a reminder with a date and time.", color = Muted, fontSize = 13.sp)
        val open = reminders.filter { !it.done }
        if(open.isEmpty()) Text("No upcoming reminders.", color = Muted)
        open.forEach { reminder -> PaperRow(reminder.title, DateFormat.getDateTimeInstance().format(Date(reminder.whenMillis)), "Done") { vm.completeReminder(reminder.id) } }
        reminders.filter { it.done }.take(5).forEach { reminder -> PaperRow(reminder.title, "Completed", "Undo") { vm.undoReminder(reminder.id) } }
    }
    editing?.let { note -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Edit note") }, text = {
        Column { OutlinedTextField(title, { title = it }, label = { Text("Title") }); OutlinedTextField(body, { body = it }, label = { Text("Note") }) }
    }, confirmButton = { TextButton(onClick = { vm.editNote(note.id, title, body); editing = null; title = ""; body = "" }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }) }
}

@Composable
private fun AgentTasksScreen(vm: AssistantViewModel, onOpenChat: () -> Unit) {
    val tasks by vm.agentTasks.collectAsState()
    var viewed by remember { mutableStateOf<AgentTask?>(null) }
    Page("Tasks", "Goals and action history stay on this phone. Continue a paused goal whenever you return.") {
        if (tasks.isEmpty()) Text("Start a goal from a chat to track its progress here.", color = Muted)
        tasks.forEach { task ->
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(task.goal, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(task.status.replaceFirstChar { it.uppercase() } + " · " + DateFormat.getDateTimeInstance().format(Date(task.updatedAt)), color = Muted, fontSize = 12.sp)
                if (task.lastResult.isNotBlank()) Text(task.lastResult, color = Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick = { viewed = task }) { Text("History") }
                    TextButton(onClick = { vm.resumeGoal(task.id); onOpenChat() }) { Text(if(task.status == "done") "Follow up" else "Continue") }
                    if(task.status == "active") TextButton(onClick = { vm.pauseGoal(task.id) }) { Text("Pause") }
                    if(task.status != "done") TextButton(onClick = { vm.completeGoal(task.id) }) { Text("Complete") }
                }
            }
        }
    }
    viewed?.let { task -> AlertDialog(onDismissRequest = { viewed = null }, title = { Text("Task history") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { vm.taskEvents(task.id).forEach { Text(it, Modifier.padding(vertical = 6.dp), color = Ink) } }
    }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Done") } }) }
}

@Composable
private fun ScheduledTasksScreen(vm: AssistantViewModel) {
    val activity = LocalContext.current as ComponentActivity
    val tasks by vm.scheduledTasks.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var whenMillis by remember { mutableStateOf<Long?>(null) }
    var repeat by remember { mutableStateOf("once") }
    var repeatMenu by remember { mutableStateOf(false) }
    var viewed by remember { mutableStateOf<ScheduledTask?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); vm.refresh() } }
    Page("Scheduled tasks", "Cina runs a saved prompt on this phone. It can create notes, reminders, and tasks while running, then notifies you of the result. Runs may start later than requested.") {
        SectionTitle("New task")
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(prompt, { prompt = it }, label = { Text("What should Cina do?") },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val calendar = Calendar.getInstance()
                android.app.DatePickerDialog(activity, { _, year, month, day ->
                    android.app.TimePickerDialog(activity, { _, hour, minute ->
                        val chosen = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
                        }
                        whenMillis = chosen.timeInMillis
                    }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }) { Text(whenMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Choose date & time") }
            Spacer(Modifier.width(10.dp))
            Box {
                TextButton(onClick = { repeatMenu = true }) { Text(when(repeat) { "daily" -> "Daily ⌄"; "weekly" -> "Weekly ⌄"; else -> "Once ⌄" }) }
                DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                    listOf("once" to "Once", "daily" to "Daily", "weekly" to "Weekly").forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { repeat = value; repeatMenu = false })
                    }
                }
            }
        }
        Text(if(selectedModel == null) "Choose a model first in Models." else "Uses the model selected now. Scheduled prompts produce text; they do not run connected tools.",
            color = Muted, fontSize = 12.sp)
        Button(onClick = {
            vm.addScheduledTask(title, prompt, whenMillis!!, repeat)
            title = ""; prompt = ""; whenMillis = null; repeat = "once"
        }, enabled = title.isNotBlank() && prompt.isNotBlank() && whenMillis?.let { it > System.currentTimeMillis() } == true && selectedModel != null) {
            Text("Schedule task")
        }
        HorizontalDivider(color = Line)
        SectionTitle("Your tasks")
        if (tasks.isEmpty()) Text("No scheduled tasks yet.", color = Muted)
        tasks.forEach { task ->
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(task.title, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(task.prompt, color = Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(if(task.enabled) "Next: ${DateFormat.getDateTimeInstance().format(Date(task.nextRunMillis))} · ${task.repeat}"
                    else "Paused or finished", color = Muted, fontSize = 12.sp)
                if(task.lastRunMillis != null) Text("Last run: ${DateFormat.getDateTimeInstance().format(Date(task.lastRunMillis))} · ${if(task.lastError == null) "Done" else "Failed"}", color = Muted, fontSize = 12.sp)
                Row {
                    if(task.lastResult != null || task.lastError != null) TextButton(onClick = { viewed = task }) { Text("View result") }
                    TextButton(onClick = { vm.setScheduledTaskEnabled(task, !task.enabled) }) { Text(if(task.enabled) "Pause" else "Resume") }
                    TextButton(onClick = { vm.deleteScheduledTask(task.id) }) { Text("Delete", color = Muted) }
                }
            }
        }
    }
    viewed?.let { task ->
        AlertDialog(onDismissRequest = { viewed = null }, containerColor = Paper, title = { Text(task.title) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if(task.lastError != null) Text(task.lastError, color = MaterialTheme.colorScheme.error)
                else AssistantResponse(task.lastResult.orEmpty())
            } }, confirmButton = { TextButton(onClick = { viewed = null }) { Text("Done") } })
    }
}

@Composable
private fun MemoryScreen(vm: AssistantViewModel, onOpenChat: () -> Unit) {
    val memories by vm.memories.collectAsState(); val enabled by vm.memoryEnabled.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    var editing by remember { mutableStateOf<Memory?>(null) }; var draft by remember { mutableStateOf("") }
    Page("Memory", "You decide what Cina remembers. Everything stays on this phone.") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Remember useful details", Modifier.weight(1f), color = Ink); Switch(enabled, onCheckedChange = vm::setMemoryEnabled) }
        HorizontalDivider(color = Line)
        if (suggestions.isNotEmpty()) SectionTitle("Suggested memories")
        suggestions.forEach { suggestion ->
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(suggestion.fact, color = Ink)
                TextButton(onClick = { vm.openChat(suggestion.sourceChatId); onOpenChat() }) { Text("Open source chat") }
                Row { TextButton(onClick = { vm.approveMemory(suggestion.id) }) { Text("Remember") }; TextButton(onClick = { vm.dismissMemory(suggestion.id) }) { Text("Dismiss") } }
            }
        }
        if(memories.isEmpty()) Text("Nothing saved yet.", color = Muted)
        memories.forEach { memory ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(memory.fact, color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
                memory.sourceChatId?.let { sourceId ->
                    TextButton(onClick = { vm.openChat(sourceId); onOpenChat() }) { Text("Open source chat") }
                }
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
    val activity = LocalContext.current as ComponentActivity
    var hf by remember { mutableStateOf("") }; var brave by remember { mutableStateOf("") }
    val profile by vm.youProfile.collectAsState()
    val connections by vm.connections.collectAsState()
    val defaultWeb by vm.defaultWeb.collectAsState()
    val defaultYolo by vm.defaultYolo.collectAsState()
    val alwaysAllowed by vm.alwaysAllowed.collectAsState()
    var expandedConnection by remember { mutableStateOf<String?>(null) }
    var mcpToken by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var customToken by remember { mutableStateOf("") }
    Page("Settings", "Just the essentials. Cina's conversations and personal data stay on your phone.") {
        CompanionSettings(profile, vm::saveCompanionSettings)
        HorizontalDivider(color = Line); Spacer(Modifier.height(4.dp))
        SectionTitle("New chats")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Web search", Modifier.weight(1f), color = Ink)
            Switch(defaultWeb, onCheckedChange = vm::setDefaultWeb)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("YOLO mode", Modifier.weight(1f), color = Ink)
            Switch(defaultYolo, onCheckedChange = vm::setDefaultYolo)
        }
        Text("These choices apply when you create a new chat. YOLO mode runs actions without asking.", color = Muted, fontSize = 12.sp)
        HorizontalDivider(color = Line); Spacer(Modifier.height(4.dp))
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
        Text("When enabled in a chat, Cina searches DuckDuckGo without a key. If it is unavailable, a saved Brave key lets Cina retry there. Search queries go to the provider used.", color = Muted, fontSize = 13.sp)
        OutlinedTextField(brave, { brave = it }, label = { Text("Brave Search API key") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.setKey("brave", brave); brave = "" }, enabled = brave.isNotBlank()) { Text("Save fallback key") }
        Text("Keys are encrypted and stored on this device.", color = Muted, fontSize = 12.sp)
        HorizontalDivider(color = Line); Spacer(Modifier.height(4.dp))
        SectionTitle("Connected tools")
        Text("Connect a service to let Cina use its available tools. Actions ask before they run unless you always allow that tool or enable YOLO mode.", color = Muted, fontSize = 13.sp)
        if (alwaysAllowed.isNotEmpty()) {
            Text("Always allowed tools", color = Ink, fontWeight = FontWeight.SemiBold)
            alwaysAllowed.sorted().forEach { key ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(key.removePrefix("local:").removePrefix("mcp:").replace(":", " · "), Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { vm.revokeAlwaysAllowed(key) }) { Text("Ask again") }
                }
            }
        }
        Text("Sign in with Linear directly. Other services currently need a token; Google Workspace also requires Google Cloud OAuth setup.", color = Muted, fontSize = 12.sp)
        Text("Add an MCP server", color = Ink, fontWeight = FontWeight.SemiBold)
        Text("Use the HTTPS URL from a Claude or Codex MCP setup. Servers configured with a local command need a hosted HTTP endpoint.", color = Muted, fontSize = 12.sp)
        OutlinedTextField(customName, { customName = it }, label = { Text("Server name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(customUrl, { customUrl = it }, label = { Text("MCP HTTPS URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(customToken, { customToken = it }, label = { Text("Bearer token (if needed)") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { vm.addMcpServer(customName, customUrl, customToken) },
            enabled = customName.isNotBlank() && customUrl.isNotBlank()) { Text("Connect server") }
        connections.forEach { connection ->
            val preset = connection.preset
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.title, color = Ink, fontWeight = FontWeight.SemiBold)
                        if(preset.id.startsWith("custom_")) Text(preset.url, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if(connection.connected && connection.toolCount > 0) "Connected · ${connection.toolCount} tools" else if(connection.connected) "Reconnect to load tools" else "Not connected", color = Muted, fontSize = 12.sp)
                    }
                    if(connection.connected) TextButton(onClick = { vm.disconnectMcp(preset.id); expandedConnection = null; mcpToken = "" }) { Text("Disconnect") }
                    TextButton(onClick = { expandedConnection = if(expandedConnection == preset.id) null else preset.id; mcpToken = "" }) {
                        Text(if(connection.connected) "Renew" else if(preset.id == "linear") "Sign in" else "Connect")
                    }
                }
                if(preset.id.startsWith("custom_")) TextButton(onClick = { vm.removeMcpServer(preset.id); expandedConnection = null; mcpToken = "" }) { Text("Remove server") }
                if(expandedConnection == preset.id) {
                    if(preset.id == "linear") {
                        Button(onClick = { vm.startLinearSignIn(activity); expandedConnection = null }) { Text("Sign in with Linear") }
                        Text("Or connect with an API key or existing OAuth token", color = Muted, fontSize = 12.sp)
                    } else Text(preset.hint, color = Muted, fontSize = 12.sp)
                    OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text(if(preset.id == "linear") "API key or access token" else if(preset.id.startsWith("custom_")) "Bearer token (if needed)" else "Access token") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { vm.connectMcp(preset.id, mcpToken); mcpToken = ""; expandedConnection = null }, enabled = mcpToken.isNotBlank() || preset.id.startsWith("custom_")) { Text("Connect") }
                }
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String = if(bytes < 0) "Size unknown" else when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
