package dev.pocketmuse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ChatDraftSaver = listSaver<SnapshotStateMap<Long, String>, Any>(
    save = { drafts -> drafts.entries.flatMap { listOf(it.key, it.value) } },
    restore = { values -> mutableStateMapOf<Long, String>().apply {
        values.chunked(2).forEach { put(it[0] as Long, it[1] as String) }
    } }
)

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
    override fun onResume() { super.onResume(); ReminderReceiver.recover(this); vm.refresh(); TodayWidget.updateAll(this) }
    override fun onStop() { vm.endSession(); super.onStop() }

    private fun handleWidgetIntent(intent: Intent?) {
        val prompt = intent?.getStringExtra(EXTRA_WIDGET_PROMPT)?.trim()?.takeIf { it.isNotEmpty() }
        if (prompt != null) {
            vm.newChat()
            widgetDraft = if (vm.models.value.any { it.path == vm.selectedModel.value }) {
                if (vm.send(prompt)) null else prompt
            } else prompt
            widgetDraftKey++
            widgetPageRequest = ++widgetRequestId to "Chat"
            return
        }
        intent?.getStringExtra(EXTRA_WIDGET_PAGE)?.let { page ->
            if (page in setOf("Notes", "Scheduled tasks", "Reminders", "Chat")) widgetPageRequest = ++widgetRequestId to page
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
    var page by rememberSaveable { mutableStateOf("Chat") }
    var modelReturnPage by rememberSaveable { mutableStateOf("Settings") }
    var companionReturnPage by rememberSaveable { mutableStateOf("Settings") }
    var taskTab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val pageState = rememberSaveableStateHolder()
    val status by vm.status.collectAsState()
    val pending by vm.pending.collectAsState()
    val profile by vm.youProfile.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    LaunchedEffect(scheduledTaskOpenRequest) {
        if (scheduledTaskOpenRequest > 0) { page = "Tasks"; taskTab = 2 }
    }
    LaunchedEffect(widgetPageRequest?.first) {
        widgetPageRequest?.let {
            when (it.second) {
                "Scheduled tasks" -> { page = "Tasks"; taskTab = 2 }
                "Reminders" -> { page = "Tasks"; taskTab = 1 }
                else -> page = it.second
            }
        }
    }
    fun back() {
        page = when (page) {
            "Models" -> modelReturnPage
            "Companion" -> companionReturnPage
            "Connections", "Chat preferences", "About" -> "Settings"
            "Edit profile" -> "You"
            else -> "Chat"
        }
    }
    BackHandler(enabled = menuOpen || page != "Chat") { if (menuOpen) menuOpen = false else back() }
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (page != "Chat") {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("‹", "Go back") { back() }
                    Spacer(Modifier.width(12.dp))
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 26.sp, color = Ink)
                    Spacer(Modifier.weight(1f))
                    RoundControl("☰", "Open menu") { menuOpen = true }
                }
            }
            AnimatedVisibility(status.isNotBlank(), enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).background(Soft, RoundedCornerShape(14.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, Modifier.weight(1f).padding(vertical = 10.dp), color = Ink, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearStatus) { Text("Close") }
                }
            }
            AnimatedContent(targetState = page, modifier = Modifier.weight(1f), transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 12 }) togetherWith fadeOut(tween(90))
            }, label = "Cina pages") { destination ->
                pageState.SaveableStateProvider(destination) {
                    when (destination) {
                        "Chat" -> ChatScreen(vm,
                            onModels = { modelReturnPage = "Chat"; page = "Models" },
                            onMenu = { menuOpen = true }, onCompanion = { companionReturnPage = "Chat"; page = "Companion" },
                            widgetDraft = widgetDraft, widgetDraftKey = widgetDraftKey)
                        "Tasks" -> TasksScreen(vm, taskTab, { taskTab = it }, onOpenChat = { page = "Chat" })
                        "Notes" -> NotesScreen(vm)
                        "You" -> YouScreen(vm, onEditProfile = { page = "Edit profile" }, onOpenChat = { page = "Chat" })
                        "Edit profile" -> ProfileEditor(profile, onSave = { vm.saveYouProfile(it); page = "You" })
                        "Models" -> ModelsScreen(vm)
                        "Connections" -> ConnectionsScreen(vm)
                        "Companion" -> Page("Companion", "Make Cina your own.") { CompanionSettings(profile, vm::saveCompanionSettings) }
                        "Chat preferences" -> ChatPreferencesScreen(vm)
                        "About" -> AboutScreen(updateStatus, onCheckUpdates)
                        else -> SettingsScreen(vm, onNavigate = {
                            if (it == "Models") modelReturnPage = "Settings"
                            if (it == "Companion") companionReturnPage = "Settings"
                            page = it
                        })
                    }
                }
            }
        }
        AnimatedVisibility(menuOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(130))) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .18f)).clickable { menuOpen = false })
        }
        AnimatedVisibility(menuOpen, enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(180))) {
            Surface(Modifier.fillMaxHeight().fillMaxWidth(.82f), color = Paper, shadowElevation = 10.dp) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 38.sp, color = Ink)
                    Text("A little space to think.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(32.dp))
                    listOf("Chat" to "Conversations & files", "Tasks" to "Goals, reminders & schedules",
                        "Notes" to "Things worth keeping", "You" to "Profile & memory", "Settings" to "Models, connections & appearance").forEach { (destination, detail) ->
                        val selected = page == destination
                        Row(Modifier.fillMaxWidth().background(if (selected) Soft else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { page = destination; menuOpen = false }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(destination, fontSize = 19.sp, color = if (selected) Accent else Ink, fontWeight = FontWeight.Medium)
                                Text(detail, color = Muted, fontSize = 11.sp)
                            }
                            if (destination == "You" && suggestions.isNotEmpty()) Text("${suggestions.size}", color = Accent)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(color = Line)
                    Text("Private by design. Here with you.", Modifier.padding(top = 18.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    pending?.let { action -> ActionApprovalDialog(action, onReject = vm::reject, onApprove = { vm.approve() }, onAlwaysAllow = { vm.approve(always = true) }) }
    if (availableUpdate != null && pending == null) AlertDialog(onDismissRequest = onDismissUpdate, containerColor = Paper,
        title = { Text("Cina update available") }, text = { Text("${availableUpdate.version} is ready. Download the APK to update Cina.") },
        confirmButton = { TextButton(onClick = { onDownloadUpdate(availableUpdate) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismissUpdate) { Text("Later") } })
}

@Composable
internal fun RoundControl(symbol: String, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Soft, modifier = Modifier.size(48.dp).semantics { contentDescription = description }.clickable(onClickLabel = description, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 23.sp, color = Ink) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: AssistantViewModel, onModels: () -> Unit, onMenu: () -> Unit, onCompanion: () -> Unit,
    widgetDraft: String?, widgetDraftKey: Int) {
    val chats by vm.chats.collectAsState(); val active by vm.active.collectAsState(); val messages by vm.messages.collectAsState()
    val live by vm.live.collectAsState(); val busy by vm.busy.collectAsState(); val logs by vm.logs.collectAsState()
    val selected by vm.selectedModel.collectAsState(); val models by vm.models.collectAsState()
    val attachments by vm.attachments.collectAsState(); val profile by vm.youProfile.collectAsState()
    val benchmarking by vm.benchmarking.collectAsState(); val pending by vm.pending.collectAsState()
    val tasks by vm.agentTasks.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.attachFile(uri) }
    val chat = chats.firstOrNull { it.id == active }
    val modelName = models.firstOrNull { it.path == selected }?.name
    val drafts = rememberSaveable(saver = ChatDraftSaver) { mutableStateMapOf<Long, String>() }
    val text = drafts[active].orEmpty()
    var handledWidgetDraftKey by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(widgetDraftKey) {
        if (widgetDraftKey > handledWidgetDraftKey) { drafts[active] = widgetDraft.orEmpty(); handledWidgetDraftKey = widgetDraftKey }
    }
    var showChats by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showComposerActions by remember { mutableStateOf(false) }
    val canSend = modelName != null && !busy && benchmarking == null && pending == null
    val hasActiveGoal = tasks.any { it.chatId == active && it.status in listOf("active", "waiting") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty() || busy) listState.animateScrollToItem(if (busy) messages.size else (messages.size - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundControl("☰", "Open menu", onMenu)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClickLabel = "Open conversations") { showChats = true }) {
                Text("cina", fontFamily = FontFamily.Serif, fontSize = 27.sp, color = Ink)
                Text("${chat?.title ?: "New chat"} ⌄", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundControl("＋", "New chat") { vm.newChat() }
            if (profile.showPet) CompanionBubble(profile, 40.dp, Modifier.clickable(onClickLabel = "Customize companion", onClick = onCompanion))
            Box {
                RoundControl("⋯", "Chat options") { showOptions = true }
                DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }, containerColor = Paper) {
                    DropdownMenuItem(text = { Text(if (chat?.web == true) "Turn off web search" else "Turn on web search") },
                        onClick = { vm.setFlag("web", chat?.web != true); showOptions = false })
                    DropdownMenuItem(text = { Text(if (chat?.yolo == true) "Ask before actions" else "Run actions without asking") },
                        onClick = { vm.setFlag("yolo", chat?.yolo != true); showOptions = false })
                    HorizontalDivider(color = Line)
                    DropdownMenuItem(text = { Text("Recent actions") }, onClick = { showOptions = false; showLog = true })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showModels = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp), modifier = Modifier.weight(1f)) {
                Text("${modelName ?: "Choose a model"} ⌄", color = Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chat?.web == true) Text("WEB ON", color = Accent, fontSize = 10.sp, modifier = Modifier.padding(start = 12.dp))
            if (chat?.yolo == true) TextButton(onClick = { vm.stop(); vm.setFlag("yolo", false) }) { Text("Stop auto actions", fontSize = 11.sp) }
        }
        if (chat?.yolo == true) Text("Actions run without asking", Modifier.padding(horizontal = 20.dp), color = Muted, fontSize = 11.sp)
        if (messages.isEmpty() && !busy) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A little space to think.", fontFamily = FontFamily.Serif, fontSize = 30.sp, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    Text(if (modelName == null) "Start with a model that runs on your phone." else "Ask a question, save a thought, or make a plan.",
                        color = Muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    if (modelName == null) Button(onClick = onModels) { Text("Get a recommended model") }
                    else {
                        listOf("Help me plan my week", "Save a note", "Set a reminder").forEach { suggestion ->
                            TextButton(onClick = { drafts[active] = suggestion }) { Text(suggestion, color = Accent) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(messages, key = { it.id }) { message ->
                    if (message.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = Warm, shape = RoundedCornerShape(22.dp, 22.dp, 5.dp, 22.dp)) {
                                SelectionContainer { Text(message.body, Modifier.widthIn(max = 310.dp).padding(horizontal = 17.dp, vertical = 13.dp), color = Ink, fontSize = 15.sp, lineHeight = 23.sp) }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().animateContentSize()) {
                            Text("CINA", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp)); AssistantResponse(message.body)
                        }
                    }
                }
                if (busy) item {
                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                        Text("CINA", color = Accent, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        if (live.isBlank()) WorkingIndicator() else AssistantResponse(live, streaming = true)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (attachments.isNotEmpty()) Text("${attachments.size} file${if (attachments.size == 1) "" else "s"} · ${attachments.joinToString { it.name }}",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).padding(4.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { showComposerActions = true }, enabled = !busy) {
                        Text("＋", fontSize = 24.sp, color = Accent, modifier = Modifier.semantics { contentDescription = "Message actions" })
                    }
                    DropdownMenu(expanded = showComposerActions, onDismissRequest = { showComposerActions = false }, containerColor = Paper) {
                        DropdownMenuItem(text = { Text("Attach a file") }, onClick = { showComposerActions = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Column { Text("Track as goal"); Text("Use your message to start a plan", color = Muted, fontSize = 11.sp) } },
                            enabled = text.isNotBlank() && canSend && !hasActiveGoal,
                            onClick = { showComposerActions = false; if (vm.startGoal(text)) drafts[active] = "" })
                    }
                }
                BasicTextField(value = text, onValueChange = { drafts[active] = it }, modifier = Modifier.weight(1f).padding(vertical = 13.dp)
                    .semantics { contentDescription = "Message Cina" },
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 5,
                    decorationBox = { inner -> Box { if (text.isBlank()) Text("Message Cina…", color = Muted, fontSize = 16.sp); inner() } })
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if (busy) vm.stop() else if (vm.send(text)) drafts[active] = "" }, enabled = busy || (text.isNotBlank() && canSend),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.White), modifier = Modifier.size(48.dp)) {
                    Text(if (busy) "■" else "↑", fontSize = if (busy) 16.sp else 23.sp,
                        modifier = Modifier.semantics { contentDescription = if (busy) "Stop response" else "Send message" })
                }
            }
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SectionTitle("Choose a model")
            if (models.isEmpty()) Text("Download a model to start chatting offline.", Modifier.padding(vertical = 16.dp), color = Muted)
            models.forEach { model -> PaperRow(model.name, sizeLabel(model.bytes), if (model.path == selected) "Selected" else "Use") {
                vm.setModel(model.path); showModels = false
            } }
            TextButton(onClick = { showModels = false; onModels() }, modifier = Modifier.fillMaxWidth()) { Text("Manage models") }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showChats) ModalBottomSheet(onDismissRequest = { showChats = false }, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            SectionTitle("Conversations")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(chats, key = { it.id }) { item ->
                    PaperRow(item.title, if (item.id == active) "Current chat" else "Saved on this phone", "Open") { vm.openChat(item.id); showChats = false }
                }
            }
            TextButton(onClick = { vm.newChat(); showChats = false }, modifier = Modifier.fillMaxWidth()) { Text("New chat") }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showLog) AlertDialog(onDismissRequest = { showLog = false }, containerColor = Paper, title = { Text("Recent actions") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (logs.isEmpty()) Text("Nothing yet.", color = Muted) else logs.forEach { Text(it, Modifier.padding(bottom = 14.dp), color = Ink) }
        } }, confirmButton = { TextButton(onClick = { showLog = false }) { Text("Done") } })
}

@Composable
internal fun AssistantResponse(raw: String, streaming: Boolean = false) {
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
    if (response.answer.isNotBlank()) AssistantMarkdown(response.answer)
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
