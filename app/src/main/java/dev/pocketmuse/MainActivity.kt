package dev.pocketmuse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
    private val googleAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.finishGoogleSignIn(result.data, result.resultCode != RESULT_OK)
    }
    internal fun connectGoogle(id: String) = vm.startGoogleSignIn(id) { pending ->
        googleAuthorization.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
    }
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
    override fun onResume() { super.onResume(); ReminderReceiver.recover(this); DurableWorkScheduler.recover(this); vm.refresh(); TodayWidget.updateAll(this) }
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
            if (page == "Inbox" && !BuildConfig.INBOX_ENABLED) return
            if (page in setOf("Inbox", "Notes", "Scheduled tasks", "Reminders", "Chat", "Actions", "Work")) widgetPageRequest = ++widgetRequestId to page
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
    var taskTab by rememberSaveable { mutableIntStateOf(4) }
    var workEntry by rememberSaveable { mutableIntStateOf(0) }
    var librarySource by rememberSaveable { mutableStateOf<String?>(null) }
    var inboxEntry by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var modelSlot by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(listOf<String>()) }
    var focusKind by rememberSaveable { mutableStateOf<String?>(null) }
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceReturn by rememberSaveable { mutableStateOf(false) }
    var setupModel by rememberSaveable { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    fun navigate(target: String) {
        if(page != target) { history = history + page; page = target }
    }
    fun rootPage(target: String) { history = emptyList(); focusId=null; focusKind=null; sourceReturn=false; if(target=="Tasks") { taskTab=4;workEntry++ }; page=target }
    val durable: DurableWorkViewModel = viewModel()
    val localWork by durable.tasks.collectAsState()
    val goals by vm.agentTasks.collectAsState()
    val schedules by vm.scheduledTasks.collectAsState()
    val homeJobs by vm.homeJobs.collectAsState()
    LaunchedEffect(page) { vm.refresh();durable.refresh() }
    val workCount = localWork.count { it.state in setOf("waiting_for_approval","waiting_for_input") } + goals.count { it.status=="waiting" } + schedules.count { !it.lastError.isNullOrBlank() } + homeJobs.count { it.state in setOf("failed","interrupted") || it.error.isNotBlank() }
    val pageState = rememberSaveableStateHolder()
    val status by vm.status.collectAsState()
    val pending by vm.pending.collectAsState()
    val profile by vm.youProfile.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    LaunchedEffect(scheduledTaskOpenRequest) {
        if (scheduledTaskOpenRequest > 0) { workEntry++;page = "Tasks"; taskTab = 2 }
    }
    LaunchedEffect(widgetPageRequest?.first) {
        widgetPageRequest?.let {
            if(it.second in setOf("Scheduled tasks","Reminders","Actions","Work")) workEntry++
            when (it.second) {
                "Scheduled tasks" -> { page = "Tasks"; taskTab = 2 }
                "Reminders" -> { page = "Tasks"; taskTab = 1 }
                "Actions" -> { page = "Tasks"; taskTab = 3 }
                "Work" -> { page = "Tasks"; taskTab = 4 }
                else -> page = it.second
            }
        }
    }
    fun back() {
        if(page=="Models" && modelSlot!=null) { modelSlot=null; return }
        if(history.isNotEmpty()) { page=history.last(); history=history.dropLast(1) }
        else page=when(page) {
            "Inbox","Notes","Knowledge","Memory" -> "Library"
            "Connections","Skills","Chat preferences","About","Response settings","You","Library storage","Companion" -> "Settings"
            else -> "Chat"
        }
    }
    BackHandler(enabled = menuOpen || page != "Chat") { if (menuOpen) menuOpen = false else back() }
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (page != "Chat") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundControl("‹", "Go back") { backDispatcher?.onBackPressed() }
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
                            onModels = { modelReturnPage = "Chat"; modelSlot = null; setupModel=vm.models.value.isEmpty(); navigate("Models") },
                            onMenu = { menuOpen = true }, onWork = { id -> focusKind="home"; focusId=id; navigate("Tasks") },
                            widgetDraft = widgetDraft, widgetDraftKey = widgetDraftKey)
                        "Tasks" -> TasksScreen(vm, taskTab, { taskTab = it }, onOpenChat = { navigate("Chat") },
                            onOpenResult = { kind,id -> vm.refresh(); focusKind=kind; focusId=id.toString(); if(kind=="note") navigate("Notes") else taskTab=1 },
                            openKind=focusKind?.takeIf { page=="Tasks" },openId=focusId?.takeIf { page=="Tasks" },onConsumed={ focusKind=null;focusId=null },entryKey=workEntry)
                        "Library" -> LibraryScreen(vm,onOpen={ kind,id ->
                            focusKind=kind;focusId=id
                            when(kind) {
                                "source" -> { librarySource=id;sourceReturn=true;navigate("Inbox") }
                                "note" -> navigate("Notes")
                                "memory" -> navigate("Memory")
                                else -> navigate("Knowledge")
                            }
                        }) { target -> focusId=null;focusKind=null;sourceReturn=false;if(target=="Inbox") inboxEntry++;navigate(target) }
                        "Inbox" -> if (BuildConfig.INBOX_ENABLED) InboxScreen(sourceId = librarySource, onSourceOpened = { librarySource = null }, returnToOrigin=sourceReturn, onLeaveSource={ sourceReturn=false;back() },captureRequest=inboxEntry)
                        "Notes" -> NotesScreen(vm,openId=focusId?.takeIf { page=="Notes" }?.toLongOrNull(),startNew=page=="Notes" && focusId=="new",onOpened={ focusId=null })
                        "Knowledge" -> if (BuildConfig.INBOX_ENABLED) ContextLibraryScreen(viewModel(), onBack = { back() }, onOpenSource = { id -> librarySource = id;sourceReturn=true;navigate("Inbox") }, backLabel = "Back to Library", selectedId=focusId, selectionKind=focusKind)
                        "Memory" -> Page("Memory", "The personal details you choose to keep.") { MemoryContent(vm, onOpenChat = { navigate("Chat") },focusId=focusId?.toLongOrNull()) }
                        "Library storage" -> if (BuildConfig.INBOX_ENABLED) LibraryStorageScreen(onBack = { back() })
                        "You" -> YouScreen(vm, onEditProfile = { navigate("Edit profile") }, onMemory={ focusId=null;navigate("Memory") })
                        "Edit profile" -> ProfileEditor(profile, onSave = { vm.saveYouProfile(it); back() })
                        "Models" -> ModelsScreen(vm, modelSlot?.let { ModelSlot.valueOf(it) }, { modelSlot = it?.name }, onConnectHome = { navigate("Connections") }, setup=setupModel, onReady={ setupModel=false;modelSlot=null;back() })
                        "Skills" -> SkillsScreen(vm)
                        "Connections" -> ConnectionsScreen(vm)
                        "Companion" -> Page("Appearance", "Make Cina your own.") { CompanionSettings(profile, vm::saveCompanionSettings) }
                        "Response settings" -> Page("Responses", "Choose speed and level of detail.") { ResponseSettings(vm, onModels = { modelReturnPage = "Response settings"; modelSlot = null;setupModel=false; navigate("Models") }) }
                        "Chat preferences" -> ChatPreferencesScreen(vm)
                        "About" -> AboutScreen(updateStatus, onCheckUpdates)
                        else -> SettingsScreen(vm, onNavigate = {
                            if (it == "Models") { modelReturnPage = "Settings"; modelSlot = null }
                            if (it == "Companion") companionReturnPage = "Settings"
                            setupModel=false; navigate(it)
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
                Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("cina", fontFamily = FontFamily.Serif, fontSize = 38.sp, color = Ink)
                    Text("A little space to think.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(32.dp))
                    listOf("Chat" to "Conversations & files", "Library" to "Inbox, notes, knowledge & memory", "Work" to "Progress, approvals & results",
                        "Settings" to "Profile, models, connections & appearance").forEach { (label, detail) ->
                        val destination = if (label == "Work") "Tasks" else label
                        val selected = page == destination || destination == "Library" && page in setOf("Inbox", "Notes", "Knowledge", "Memory", "Library storage")
                        Row(Modifier.fillMaxWidth().background(if (selected) Soft else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { rootPage(destination); menuOpen = false }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 19.sp, color = if (selected) Accent else Ink, fontWeight = FontWeight.Medium)
                                Text(detail, color = Muted, fontSize = 11.sp)
                            }
                            if (destination == "Library" && suggestions.isNotEmpty()) Text("${suggestions.size}", color = Accent)
                            if (destination == "Tasks" && workCount>0) Text("$workCount",color=Accent,modifier=Modifier.semantics { contentDescription="$workCount items need your attention" })
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
    pending?.let { action -> ActionApprovalDialog(action, vm.pendingItemLabel(action.request), vm.pendingNote(action.request), vm::approvalError, onReject = vm::reject, onApprove = { request, always -> vm.approve(always, request) }) }
    if (availableUpdate != null && pending == null) AlertDialog(onDismissRequest = onDismissUpdate, containerColor = Paper,
        title = { Text("Cina update available") }, text = { Text("${availableUpdate.version} is ready. Download the APK to update Cina.") },
        confirmButton = { TextButton(onClick = { onDownloadUpdate(availableUpdate) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismissUpdate) { Text("Later") } })
}

@Composable
internal fun RoundControl(symbol: String, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Soft, modifier = Modifier.size(48.dp).semantics { contentDescription = description }.clickable(role=androidx.compose.ui.semantics.Role.Button,onClickLabel = description, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { CinaIcon(symbol,Modifier.size(24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: AssistantViewModel, onModels: () -> Unit, onMenu: () -> Unit, onWork: (String) -> Unit,
    widgetDraft: String?, widgetDraftKey: Int) {
    val chats by vm.chats.collectAsState(); val active by vm.active.collectAsState(); val messages by vm.messages.collectAsState()
    val live by vm.live.collectAsState(); val busy by vm.busy.collectAsState(); val logs by vm.logs.collectAsState()
    val selected by vm.selectedModel.collectAsState(); val models by vm.models.collectAsState()
    val home by vm.homeState.collectAsState()
    val homeJobs by vm.homeJobs.collectAsState()
    HomeRefresh(vm)
    val attachments by vm.attachments.collectAsState(); val profile by vm.youProfile.collectAsState()
    val benchmarking by vm.benchmarking.collectAsState(); val pending by vm.pending.collectAsState()
    val tasks by vm.agentTasks.collectAsState()
    val turns by vm.turns.collectAsState()
    val stage by vm.stage.collectAsState()
    val memoryEnabled by vm.memoryEnabled.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.attachFile(uri) }
    val chat = chats.firstOrNull { it.id == active }
    val modelName = home.selected?.let { "$it · Home" } ?: models.firstOrNull { it.path == selected }?.name
    val drafts = rememberSaveable(saver = ChatDraftSaver) { mutableStateMapOf<Long, String>() }
    val text = drafts[active].orEmpty()
    var handledWidgetDraftKey by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(widgetDraftKey) {
        if (widgetDraftKey > handledWidgetDraftKey) { drafts[active] = widgetDraft.orEmpty(); handledWidgetDraftKey = widgetDraftKey }
    }
    var showChats by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var editingTurn by remember { mutableStateOf<TurnRecord?>(null) }
    var editedRequest by rememberSaveable { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showComposerActions by remember { mutableStateOf(false) }
    val canSend = modelName != null && !busy && benchmarking == null && pending == null
    val hasActiveGoal = tasks.any { it.chatId == active && it.status in listOf("active", "waiting") }
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    var followReplies by remember(active) { mutableStateOf(true) }
    var unreadReply by remember(active) { mutableStateOf(false) }
    var seenCount by remember(active) { mutableIntStateOf(messages.size) }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if(dragging) followReplies=false }
    LaunchedEffect(messages.size, busy, live.length / 80) {
        if(!followReplies && (messages.size>seenCount || busy && live.isNotBlank())) unreadReply=true
        seenCount=messages.size
        if (followReplies && (messages.isNotEmpty() || busy)) listState.scrollToItem(messages.size + if(busy) 1 else 0)
    }
    Column(Modifier.fillMaxSize()) {
        val responseMode by vm.responseMode.collectAsState()
        val resolvedMode by vm.resolvedMode.collectAsState()
        val responseModel by vm.responseModelName.collectAsState()
        val undo by vm.memoryUndo.collectAsState()
        if (undo.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Saved ${undo.size} useful detail(s)", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
            TextButton(onClick = vm::undoMemories) { Text("Undo") }
            TextButton(onClick = vm::keepMemories) { Text("Keep") }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundControl("☰", "Open menu", onMenu)
            Spacer(Modifier.width(12.dp))
            if (profile.showPet) {
                CompanionBubble(profile, 28.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).clickable(onClickLabel = "Open conversations") { showChats = true }) {
                Text("${chat?.title ?: "New chat"} ⌄",fontFamily=FontFamily.Serif,fontSize=23.sp,color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            RoundControl("＋", "New chat") { vm.newChat() }
            Spacer(Modifier.width(4.dp))
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
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            var responseOptions by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick={ responseOptions=true }) { Text("${responseMode.name.lowercase().replaceFirstChar { it.uppercase() }} ⌄",color=Accent) }
                DropdownMenu(responseOptions,{ responseOptions=false }) {
                    ResponseMode.entries.forEach { mode -> DropdownMenuItem(text={ Text(when(mode) { ResponseMode.AUTO -> "Auto · choose for me";ResponseMode.QUICK -> "Quick · everyday questions";ResponseMode.DEEP -> "Deep · complex work" }) },onClick={ vm.setResponseMode(mode);responseOptions=false }) }
                    HorizontalDivider(color=Line)
                    DropdownMenuItem(text={ Text(modelName ?: "Set up a phone model") },onClick={ responseOptions=false;showModels=true })
                    DropdownMenuItem(text={ Text("Manage models") },onClick={ responseOptions=false;onModels() })
                }
            }
            Spacer(Modifier.weight(1f))
            if(chat?.web==true) TextButton(onClick={ vm.setFlag("web",false) }) { Text("Web on",fontSize=14.sp) }
            if(chat?.yolo==true) TextButton(onClick={ vm.stop();vm.setFlag("yolo",false) }) { Text("Stop auto actions") }
        }
        if(home.selected != null) Text("On Home · selected chat excerpts and file passages are shared",Modifier.padding(horizontal=20.dp),color=Muted,fontSize=14.sp)
        homeJobs.firstOrNull { it.chat==active && !it.terminal }?.let { job ->
            NavigationRow("Home · ${workState(job.state)}",job.error.ifBlank { job.snapshot.optString("detail").ifBlank { "Open work details" } }) { onWork(job.id) }
        }
        if (messages.isEmpty() && !busy) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val compact=maxHeight<240.dp
                val showHint=maxHeight>=110.dp
                Column(Modifier.verticalScroll(rememberScrollState()).padding(if(compact) 8.dp else 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if(!compact) {
                        Text("A little space to think.", fontFamily = FontFamily.Serif, fontSize = 30.sp, color = Ink)
                        Spacer(Modifier.height(10.dp))
                    }
                    if(showHint) Text(if(compact) { if(modelName==null) "Set up Cina for offline chat." else "What would you like to do?" } else if (modelName == null) "Start with a model that runs on your phone." else "Ask a question, save a thought, or make a plan.",
                        color = Muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(if(compact) 8.dp else 20.dp))
                    if (modelName == null) Button(onClick = onModels) { Text("Get a recommended model") }
                    else if(!compact) {
                        listOf("Help me plan my week", "Save a note", "Set a reminder").forEach { suggestion ->
                            TextButton(onClick = { drafts[active] = suggestion }) { Text(suggestion, color = Accent) }
                        }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().semantics { contentDescription="Conversation messages" },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(messages, key = { it.id }) { message ->
                    if (message.role == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = Warm, shape = RoundedCornerShape(22.dp, 22.dp, 5.dp, 22.dp)) {
                                SelectionContainer { Text(message.body, Modifier.widthIn(max = 310.dp).padding(horizontal = 17.dp, vertical = 13.dp), color = Ink, fontSize = 15.sp, lineHeight = 23.sp) }
                            }
                        }
                        turns.firstOrNull { it.id == message.id && it.status in setOf("failed", "interrupted") }?.let { turn ->
                            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(12.dp)) {
                                Text(turn.error, color = Ink, fontSize = 13.sp)
                                Row {
                                    TextButton(onClick = { vm.retryTurn(turn.id) }, enabled = canSend) { Text("Retry") }
                                    TextButton(onClick = { editingTurn = turn; editedRequest = turn.request }, enabled = canSend) { Text("Edit request") }
                                }
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
                        Text(stage, color = Muted, fontSize = 12.sp)
                        if (live.isBlank()) WorkingIndicator() else AssistantResponse(live, streaming = true)
                    }
                }
                item(key="conversation-end") { Spacer(Modifier.height(1.dp)) }
            }
        }
        if(!atBottom && !followReplies) TextButton(onClick={ unreadReply=false;followReplies=true;chatScope.launch { listState.animateScrollToItem(messages.size + if(busy) 1 else 0) } },modifier=Modifier.align(Alignment.CenterHorizontally)) { Text(if(unreadReply) "New reply ↓" else "Latest messages ↓") }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (attachments.isNotEmpty()) TextButton(onClick = { showFiles = true }) { Text("${attachments.size} attached file${if (attachments.size == 1) "" else "s"} · Review", fontSize = 12.sp) }
            if (messages.isEmpty() && text.isBlank()) Text(if (memoryEnabled) "Memory on · Review new details in Library → Memory." else "Memory off · Saved details are not used or collected.", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(28.dp)).padding(4.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { showComposerActions = true }, enabled = !busy,modifier=Modifier.semantics { contentDescription="Message actions" }) {
                        CinaIcon("＋",Modifier.size(24.dp),Accent)
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
                FilledIconButton(onClick = { if (busy) vm.stop() else if (vm.send(text)) { followReplies=true;drafts[active] = "" } }, enabled = busy || (text.isNotBlank() && canSend),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent, contentColor = Color.White), modifier = Modifier.size(48.dp).semantics { contentDescription = if (busy) "Stop response" else "Send message" }) {
                    CinaIcon(if(busy) "■" else "↑",Modifier.size(24.dp),Color.White)
                }
            }
        }
    }
    if (showModels) ModalBottomSheet(onDismissRequest = { showModels = false }, containerColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            SectionTitle("Choose a model")
            if (models.isEmpty()) Text("Download a model to start chatting offline.", Modifier.padding(vertical = 16.dp), color = Muted)
            home.models.forEach { model -> PaperRow(model.name, "Home · analysis", if(home.selected == model.name) "Selected" else "Use") {
                vm.setHomeModel(model.name); showModels = false
            } }
            models.forEach { model -> PaperRow(model.name, "Phone · ${sizeLabel(model.bytes)}", if (model.path == selected && home.selected == null) "Selected" else "Use") {
                vm.setModel(model.path); showModels = false
            } }
            TextButton(onClick = { showModels = false; onModels() }, modifier = Modifier.fillMaxWidth()) { Text("Manage models") }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showChats) ConversationSheet(vm) { showChats = false }
    if (showFiles) AttachmentsDialog(vm) { showFiles = false }
    editingTurn?.let { turn -> AlertDialog(onDismissRequest = { editingTurn = null }, title = { Text("Edit & retry") },
        text = { Column { Text("Completed actions stay recorded and are not undone.", color = Muted); OutlinedTextField(editedRequest, { editedRequest = it }, label = { Text("Request") }, minLines = 3) } },
        confirmButton = { TextButton(onClick = { if(vm.send(editedRequest, turn.id)) editingTurn = null }, enabled = editedRequest.isNotBlank() && canSend) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { editingTurn = null }) { Text("Cancel") } }) }
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
