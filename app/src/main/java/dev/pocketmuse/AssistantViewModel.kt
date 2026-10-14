package dev.pocketmuse

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AssistantViewModel(application: Application, private val savedState: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val attachmentImporter = AttachmentImporter(application, store)
    private val profileStore = YouProfileStore(application)
    private val skillStore = SkillStore(application)
    val secrets = SecretStore(application)
    private val mcp = McpConnections(application, secrets)
    val library = ModelLibrary(application, store, secrets)
    private var runtime: AssistantRuntime? = null
    private var tools: AssistantTools? = null
    private var task: Job? = null
    private var housekeeping: Job? = null
    private var downloadJob: Job? = null
    private var phoneBenchmarkJob: Job? = null
    private val prefs = application.getSharedPreferences("settings", 0)
    private val downloadPrefs = application.getSharedPreferences("downloads", 0)
    init { store.recoverInterruptedTurns(); store.chats().filter { it.yolo }.forEach { store.endChat(it.id) } }

    private val _chats = MutableStateFlow(store.chats())
    val chats = _chats.asStateFlow()
    private val _active = MutableStateFlow(_chats.value.firstOrNull { it.id == prefs.getLong("active_chat", -1) }?.id
        ?: _chats.value.firstOrNull { !it.archived }?.id ?: createChat().also { _chats.value = store.chats() })
    val active = _active.asStateFlow()
    private val _messages = MutableStateFlow(store.messages(_active.value))
    val messages = _messages.asStateFlow()
    private val _models = MutableStateFlow(store.models())
    val models = _models.asStateFlow()
    private val _notes = MutableStateFlow(store.notes())
    val notes = _notes.asStateFlow()
    private val _reminders = MutableStateFlow(store.reminders())
    val reminders = _reminders.asStateFlow()
    private val _scheduledTasks = MutableStateFlow(store.scheduledTasks())
    val scheduledTasks = _scheduledTasks.asStateFlow()
    private val _memories = MutableStateFlow(store.memories())
    val memories = _memories.asStateFlow()
    private val _memorySuggestions = MutableStateFlow(store.memorySuggestions())
    val memorySuggestions = _memorySuggestions.asStateFlow()
    private val _agentTasks = MutableStateFlow(store.agentTasks())
    val agentTasks = _agentTasks.asStateFlow()
    private val _attachments = MutableStateFlow(store.attachments(_active.value))
    val attachments = _attachments.asStateFlow()
    private val _benchmarks = MutableStateFlow(store.benchmarks())
    val benchmarks = _benchmarks.asStateFlow()
    private val _benchmarking = MutableStateFlow<String?>(null)
    val benchmarking = _benchmarking.asStateFlow()
    private val _phoneBenchmarkProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val phoneBenchmarkProgress = _phoneBenchmarkProgress.asStateFlow()
    private val _logs = MutableStateFlow(store.actionLog(_active.value))
    val logs = _logs.asStateFlow()
    private val _live = MutableStateFlow("")
    val live = _live.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _turns = MutableStateFlow(store.turns(_active.value))
    val turns = _turns.asStateFlow()
    private val _stage = MutableStateFlow("Preparing response")
    val stage = _stage.asStateFlow()
    private var activeTurnId: Long? = null
    private var runGeneration = 0
    private val _pending = MutableStateFlow(_turns.value.lastOrNull { it.status == "approval" }?.pending)
    val pending = _pending.asStateFlow()
    private val _selectedModel = MutableStateFlow(prefs.getString("model", null))
    val selectedModel = _selectedModel.asStateFlow()
    private val _responseMode = MutableStateFlow(runCatching { ResponseMode.valueOf(prefs.getString("response_mode", "AUTO")!!) }.getOrDefault(ResponseMode.AUTO))
    internal val responseMode = _responseMode.asStateFlow()
    private val _resolvedMode = MutableStateFlow<ResponseMode?>(null)
    internal val resolvedMode = _resolvedMode.asStateFlow()
    private val _responseModelName = MutableStateFlow<String?>(null)
    internal val responseModelName = _responseModelName.asStateFlow()
    private val _performanceSamples = MutableStateFlow(LocalPerformance(application).read())
    internal val performanceSamples = _performanceSamples.asStateFlow()
    private val _memoryUndo = MutableStateFlow(prefs.getStringSet("memory_undo", emptySet())!!.mapNotNull { it.toLongOrNull() })
    internal val memoryUndo = _memoryUndo.asStateFlow()
    private val _stalledMemories = MutableStateFlow(store.stalledMemoryCount())
    internal val stalledMemories = _stalledMemories.asStateFlow()
    internal fun retryMemoryProcessing() { store.retryMemory(); _stalledMemories.value = 0; resumeMemoryProcessing() }
    internal fun keepMemories() { _memoryUndo.value = emptyList(); prefs.edit().remove("memory_undo").apply() }
    internal fun setResponseMode(mode: ResponseMode) { if (_busy.value) return; _responseMode.value = mode; prefs.edit().putString("response_mode", mode.name).apply() }
    internal fun assignedModel(mode: ResponseMode): String? = prefs.getString("model_" + mode.name, null)
    internal fun assignModel(mode: ResponseMode, path: String?) { prefs.edit().putString("model_" + mode.name, path).apply() }
    internal fun clearPerformance() { LocalPerformance(getApplication()).clear(); _performanceSamples.value = emptyList() }
    internal fun undoMemories() { _memoryUndo.value.forEach(store::deleteMemory); _memoryUndo.value = emptyList(); prefs.edit().remove("memory_undo").apply(); runtime?.invalidate(); refresh() }
    private fun maintain(assistant: AssistantRuntime, chatId: Long) {
        assistant.performance.mark("answer_ready_ms")
        housekeeping = viewModelScope.launch {
            try { ModelAccess.mutex.withLock {
                if (_memoryEnabled.value) assistant.processMemoryJobs { id ->
                    _memoryUndo.value = (_memoryUndo.value + id).distinct()
                    prefs.edit().putStringSet("memory_undo", _memoryUndo.value.map { it.toString() }.toSet()).apply()
                    refresh()
                }
                refresh()
                kotlinx.coroutines.withTimeout(25_000) { assistant.summarizeOlderChat(chatId) }
                assistant.performance.mark("summary_finished_ms")
                kotlinx.coroutines.withTimeout(10_000) { maybeTitle(chatId, assistant) }
                assistant.performance.mark("title_finished_ms")
            } } catch (_: CancellationException) { } catch (_: Exception) { }
            finally { assistant.invalidate(); assistant.performance.finish("completed"); _performanceSamples.value = assistant.performance.read(); _stalledMemories.value = store.stalledMemoryCount() }
        }
    }
    private fun resumeMemoryProcessing() {
        if (!_memoryEnabled.value || _busy.value || store.pendingMemory().isEmpty()) return
        val model = _models.value.firstOrNull { it.path == _selectedModel.value } ?: return
        val assistant = runtime ?: return
        val chat = store.chat(_active.value) ?: return
        housekeeping?.cancel()
        housekeeping = viewModelScope.launch {
            try { ModelAccess.mutex.withLock {
                assistant.prepareMemory(model)
                assistant.processMemoryJobs { id ->
                    _memoryUndo.value = (_memoryUndo.value + id).distinct()
                    prefs.edit().putStringSet("memory_undo", _memoryUndo.value.map { it.toString() }.toSet()).apply()
                    refresh()
                }
            } } catch (_: CancellationException) { } catch (_: Exception) { }
            finally { assistant.invalidate(); _stalledMemories.value = store.stalledMemoryCount(); refresh() }
        }
    }
    private val _memoryEnabled = MutableStateFlow(prefs.getBoolean("memory", true))
    val memoryEnabled = _memoryEnabled.asStateFlow()
    private val _defaultWeb = MutableStateFlow(prefs.getBoolean("default_web", false))
    val defaultWeb = _defaultWeb.asStateFlow()
    private val _defaultYolo = MutableStateFlow(prefs.getBoolean("default_yolo", false))
    val defaultYolo = _defaultYolo.asStateFlow()
    private val _alwaysAllowed = MutableStateFlow(prefs.getStringSet("always_allowed_tools", emptySet())?.toSet().orEmpty())
    val alwaysAllowed = _alwaysAllowed.asStateFlow()
    private val _download = MutableStateFlow(DownloadState())
    val download = _download.asStateFlow()
    private val _resumable = MutableStateFlow(downloadPrefs.getString("url", null)?.let { it to (downloadPrefs.getString("name", null) ?: "model") })
    val resumable = _resumable.asStateFlow()
    private val _repoResults = MutableStateFlow<List<String>>(emptyList())
    val repoResults = _repoResults.asStateFlow()
    private val _remoteFiles = MutableStateFlow<List<RemoteModel>>(emptyList())
    val remoteFiles = _remoteFiles.asStateFlow()
    private val _popularModels = MutableStateFlow<List<PopularGgufModel>>(emptyList())
    val popularModels = _popularModels.asStateFlow()
    private val _popularModelsLoading = MutableStateFlow(false)
    val popularModelsLoading = _popularModelsLoading.asStateFlow()
    private var popularModelsJob: Job? = null
    private val _connections = MutableStateFlow(mcp.snapshot())
    val connections = _connections.asStateFlow()
    private val _connectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionStates = _connectionStates.asStateFlow()
    private fun connectionState(id: String, state: String) { _connectionStates.update { it + (id to state) } }
    private val _youProfile = MutableStateFlow(profileStore.read())
    val youProfile = _youProfile.asStateFlow()

    private val _skills = MutableStateFlow(skillStore.read())
    internal val skills = _skills.asStateFlow()

    fun setSkillEnabled(id: String, enabled: Boolean) {
        if (_busy.value) return
        skillStore.setEnabled(id, enabled); _skills.value = skillStore.read(); runtime?.invalidate()
    }
    fun saveSkillInstructions(id: String, instructions: String) {
        if (_busy.value) return
        skillStore.saveInstructions(id, instructions); _skills.value = skillStore.read(); runtime?.invalidate()
    }
    fun deleteSkill(id: String) {
        if (_busy.value) return
        skillStore.delete(id); _skills.value = skillStore.read(); runtime?.invalidate()
    }
    fun resetSkill(id: String) {
        if (_busy.value) return
        skillStore.reset(id); _skills.value = skillStore.read(); runtime?.invalidate()
    }

    fun attach(activity: Activity) {
        housekeeping?.cancel()
        tools = AssistantTools(activity, store, secrets, mcp)
        if (runtime == null) runtime = AssistantRuntime(activity.applicationContext, store) { web, request, recent, files -> tools!!.specification(web, request, recent, files) }
        resumeMemoryProcessing()
        viewModelScope.launch(Dispatchers.IO) { mcp.refreshEnabled(::connectionState); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    }
    fun connectMcp(id: String, token: String) {
        if (pendingGoogleService != null || googleSignInBusy) return
        if (_connectionStates.value[id] == "Connecting…") return
        connectionState(id, "Connecting…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.connect(id, token)
                connectionState(id, "Connected · $count tools")
                if (id == "linear") mcp.useManualLinearToken()
                _status.value = "Connected ${mcp.snapshot().first { it.preset.id == id }.preset.title}: $count tools available."
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Connection failed."; connectionState(id, "Could not connect: ${e.message}") }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun addMcpServer(name: String, url: String, token: String) {
        if(_connectionStates.value["custom"] == "Connecting…") return
        connectionState("custom", "Connecting…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.addCustom(name, url, token)
                _status.value = "Connected ${name.trim()}: $count tools available."
                connectionState("custom", "Connected · $count tools")
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Connection failed."; connectionState("custom", "Could not connect: ${e.message}") }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun removeMcpServer(id: String) {
        mcp.removeCustom(id); _connections.value = mcp.snapshot(); runtime?.invalidate()
    }
    fun startLinearSignIn(activity: Activity) {
        connectionState("linear", "Waiting for browser sign-in…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = mcp.beginLinearOAuth()
                withContext(Dispatchers.Main) { activity.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            } catch (e: Exception) { _status.value = e.message ?: "Could not start Linear sign-in."; connectionState("linear", _status.value) }
        }
    }
    private var pendingGoogleService: String?
        get() = savedState["pendingGoogleService"]
        set(value) { savedState["pendingGoogleService"] = value }
    private var googleSignInBusy = false
    internal fun startGoogleSignIn(id: String, launch: (android.app.PendingIntent) -> Unit) {
        if (pendingGoogleService != null || googleSignInBusy) return
        googleSignInBusy = true
        connectionState(id, "Waiting for Google…")
        mcp.google.client.authorize(mcp.google.request(id))
            .addOnSuccessListener { result ->
                pendingGoogleService = id
                if (result.hasResolution()) {
                    googleSignInBusy = false
                    try { launch(checkNotNull(result.pendingIntent)) }
                    catch (_: Exception) { failGoogleSignIn("Could not open Google sign-in. Try again.") }
                } else completeGoogleSignIn(id, result)
            }
            .addOnFailureListener {
                pendingGoogleService = id
                failGoogleSignIn("Google sign-in is unavailable. Check Google Play services and Cina's Google configuration, then try again.")
            }
    }
    internal fun finishGoogleSignIn(data: Intent?, cancelled: Boolean) {
        val id = pendingGoogleService ?: return
        if (cancelled) { failGoogleSignIn("Sign-in cancelled. You can try again."); return }
        try { completeGoogleSignIn(id, mcp.google.client.getAuthorizationResultFromIntent(data)) }
        catch (_: Exception) { failGoogleSignIn("Google sign-in did not finish. Try again and approve the requested permissions.") }
    }
    private fun failGoogleSignIn(message: String) {
        pendingGoogleService?.let { connectionState(it, message) }
        pendingGoogleService = null
        googleSignInBusy = false
    }
    private fun completeGoogleSignIn(id: String, result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        pendingGoogleService = null
        googleSignInBusy = true
        connectionState(id, "Connecting…")
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { mcp.completeGoogle(id, result) }
                connectionState(id, "Connected · $count tools")
                runtime?.invalidate()
            } catch (e: Exception) { connectionState(id, e.message ?: "Google connection failed. Try again.") }
            finally { _connections.value = mcp.snapshot(); googleSignInBusy = false }
        }
    }
    fun finishLinearSignIn(uri: Uri) {
        connectionState("linear", "Connecting…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.completeLinearOAuth(uri)
                _status.value = "Connected Linear: $count tools available."
                connectionState("linear", "Connected · $count tools")
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Linear sign-in failed."; connectionState("linear", _status.value) }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun disconnectMcp(id: String) { if (pendingGoogleService != null || googleSignInBusy) return; mcp.disconnect(id); connectionState(id, "Disconnected"); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    fun refresh() { prefs.edit().putLong("active_chat", _active.value).apply(); _chats.value = store.chats(); _messages.value = store.messages(_active.value); _models.value = store.models(); _notes.value = store.notes(); _reminders.value = store.reminders(); _scheduledTasks.value = store.scheduledTasks(); _memories.value = store.memories(); _memorySuggestions.value = store.memorySuggestions(); _agentTasks.value = store.agentTasks(); _attachments.value = store.attachments(_active.value); _benchmarks.value = store.benchmarks(); _logs.value = store.actionLog(_active.value); _turns.value = store.turns(_active.value); _pending.value = _turns.value.lastOrNull { it.status == "approval" }?.pending; TodayWidget.updateAll(getApplication()) }
    private fun createChat() = store.newChat(prefs.getBoolean("default_web", false), prefs.getBoolean("default_yolo", false))
    fun newChat() { stop(); store.endChat(_active.value); _active.value = createChat(); refresh() }
    fun openChat(id: Long) { if (store.chat(id) == null) return; stop(); store.endChat(_active.value); _active.value = id; refresh() }
    fun renameChat(id: Long, title: String) { if (title.isNotBlank()) { store.renameChat(id, title.trim().take(120)); refresh() } }
    fun archiveChat(id: Long, archived: Boolean) {
        if (id == _active.value && archived) stop()
        store.archiveChat(id, archived)
        if (id == _active.value && archived) _active.value = store.chats().firstOrNull { !it.archived }?.id ?: createChat()
        refresh()
    }
    fun deleteChat(id: Long, deleteMemories: Boolean) {
        if (id == _active.value) stop()
        store.deleteChat(id, deleteMemories)
        if (id == _active.value) _active.value = store.chats().firstOrNull { !it.archived }?.id ?: createChat()
        runtime?.invalidate(); refresh()
    }
    fun chatMatches(id: Long, query: String): Boolean = store.messages(id).any { it.body.contains(query, true) }
    fun attachmentText(id: Long): String = store.attachmentText(id)
    fun removeAttachment(id: Long) { store.removeAttachment(id); runtime?.invalidate(); refresh() }
    fun exportChat(id: Long, uri: Uri) = exportText(uri, buildString {
        appendLine("# ${store.chat(id)?.title.orEmpty()}")
        store.messages(id).forEach { appendLine("\n## ${if (it.role == "user") "You" else "Cina"}\n\n${splitModelResponse(it.body).answer}") }
    })
    fun exportNotes(uri: Uri) = exportText(uri, store.notes().joinToString("\n\n---\n\n") { "# ${it.title}\n\n${it.body}" })
    private fun exportText(uri: Uri, text: String) { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) } }
            _status.value = "Export saved."
        } catch (e: Exception) { _status.value = "Could not export: ${e.message}" }
    } }
    fun setFlag(flag: String, enabled: Boolean) { store.setChatFlag(_active.value, flag, enabled); refresh() }
    fun endSession() { store.endChat(_active.value); refresh() }
    fun setModel(path: String) { if (_benchmarking.value != null) return; housekeeping?.cancel(); stop(); runtime?.invalidate(); _selectedModel.value = path; prefs.edit().putString("model", path).apply() }
    fun setMemoryEnabled(enabled: Boolean) { housekeeping?.cancel(); _memoryEnabled.value = enabled; runtime?.invalidate(); prefs.edit().putBoolean("memory", enabled).apply() }
    fun setDefaultWeb(enabled: Boolean) { _defaultWeb.value = enabled; prefs.edit().putBoolean("default_web", enabled).apply() }
    fun setDefaultYolo(enabled: Boolean) { _defaultYolo.value = enabled; prefs.edit().putBoolean("default_yolo", enabled).apply() }
    private fun approvalKey(request: ToolRequest): String? = if (request.name == "mcp_call") {
        val server = request.arguments.optString("server").trim()
        val tool = request.arguments.optString("tool").trim()
        if (server.isEmpty() || tool.isEmpty()) null else "mcp:$server:$tool"
    } else request.name.takeIf { it.isNotBlank() }?.let { "local:$it" }
    private fun needsApproval(request: ToolRequest, dispatcher: AssistantTools, chat: Chat): Boolean {
        val key = approvalKey(request)
        val previousActions = store.turns(chat.id).firstOrNull { it.id == activeTurnId }?.let { it.attempts > 1 && store.hasToolHistory(it.scope) } == true
        return dispatcher.needsConfirmation(request) && (previousActions || (!chat.yolo && (key == null || key !in _alwaysAllowed.value)))
    }
    fun revokeAlwaysAllowed(key: String) {
        _alwaysAllowed.value = _alwaysAllowed.value - key
        prefs.edit().putStringSet("always_allowed_tools", _alwaysAllowed.value).apply()
    }
    fun saveYouProfile(profile: YouProfile) = persistYouProfile(profile, "Your profile is saved on this device.")
    fun saveCompanionSettings(profile: YouProfile) = persistYouProfile(profile, "Cina customization saved on this device.")
    private fun persistYouProfile(profile: YouProfile, message: String) {
        val saved = profile.copy(
            name = profile.name.trim().take(80), pronouns = profile.pronouns.trim().take(60),
            about = profile.about.trim().take(500), preferences = profile.preferences.trim().take(500),
            petName = profile.petName.trim().take(40)
        )
        profileStore.save(saved)
        _youProfile.value = saved
        runtime?.invalidate()
        _status.value = message
    }
    fun setKey(type: String, value: String) { secrets.put(type, value); _status.value = "$type key saved on this device." }
    fun addNote(title: String, body: String) { if(title.isNotBlank() && body.isNotBlank()) { store.addNote(title.trim(), body.trim()); refresh() } }
    fun deleteNote(id: Long) { store.deleteNote(id); refresh() }
    fun editNote(id: Long, title: String, body: String) { if(title.isNotBlank() && body.isNotBlank()) { store.updateNote(id, title.trim(), body.trim()); refresh() } }
    fun saveReminder(id: Long?, title: String, whenMillis: Long): Boolean {
        if (title.isBlank() || whenMillis <= System.currentTimeMillis()) return false
        val savedId = if (id == null) store.addReminder(title.trim(), whenMillis) else {
            ReminderReceiver.cancel(getApplication(), id)
            store.updateReminder(id, title.trim(), whenMillis)
            id
        }
        ReminderReceiver.schedule(getApplication(), savedId, whenMillis)
        refresh()
        return true
    }
    fun deleteReminder(id: Long) { ReminderReceiver.cancel(getApplication(), id); store.deleteReminder(id); refresh() }
    fun completeReminder(id: Long) { store.completeReminder(id); ReminderReceiver.cancel(getApplication(), id); refresh() }
    fun undoReminder(id: Long) { store.setReminderDone(id, false); store.reminders().firstOrNull { it.id == id }?.takeIf { it.whenMillis > System.currentTimeMillis() }?.let { ReminderReceiver.schedule(getApplication(), id, it.whenMillis) }; refresh() }
    fun addScheduledTask(title: String, prompt: String, whenMillis: Long, repeat: String): Boolean {
        val model = _selectedModel.value?.takeIf { path -> _models.value.any { it.path == path } }
            ?: run { _status.value = "Choose a model before scheduling a task."; return false }
        if (title.isBlank() || prompt.isBlank() || whenMillis <= System.currentTimeMillis() || repeat !in listOf("once", "daily", "weekly")) {
            _status.value = "Add a title, prompt, and future time."; return false
        }
        val id = store.addScheduledTask(title.trim().take(100), prompt.trim().take(2000), model, whenMillis, repeat)
        store.scheduledTask(id)?.let { ScheduledTaskScheduler.schedule(getApplication(), it) }
        refresh(); _status.value = "Scheduled task saved. Android may start it a little late."
        return true
    }
    fun setScheduledTaskEnabled(task: ScheduledTask, enabled: Boolean) {
        if (!enabled) {
            store.setScheduledTaskEnabled(task.id, false); ScheduledTaskScheduler.cancel(getApplication(), task.id)
        } else {
            val next = if (task.nextRunMillis > System.currentTimeMillis()) task.nextRunMillis
                else ScheduledTaskScheduler.nextRun(task.nextRunMillis, task.repeat)
            if (next == null) { _status.value = "This one-time task has passed. Create a new task to run it again."; return }
            store.setScheduledTaskEnabled(task.id, true, next)
            store.scheduledTask(task.id)?.let { ScheduledTaskScheduler.schedule(getApplication(), it) }
        }
        refresh()
    }
    fun deleteScheduledTask(id: Long) { ScheduledTaskScheduler.cancel(getApplication(), id); store.deleteScheduledTask(id); refresh() }
    fun saveSchedule(id: Long?, title: String, prompt: String, model: String, whenMillis: Long, repeat: String): Boolean {
        if (title.isBlank() || prompt.isBlank() || whenMillis <= System.currentTimeMillis() || repeat !in setOf("once", "daily", "weekly") || store.models().none { it.path == model }) return false
        if (id != null) {
            if (store.scheduledTask(id)?.running == true) { _status.value = "Pause this task before editing it."; return false }
            ScheduledTaskScheduler.cancel(getApplication(), id)
            store.editScheduledTask(id, title.trim().take(100), prompt.trim().take(2000), model, whenMillis, repeat)
        }
        val saved = id ?: store.addScheduledTask(title.trim().take(100), prompt.trim().take(2000), model, whenMillis, repeat)
        store.scheduledTask(saved)?.let { ScheduledTaskScheduler.schedule(getApplication(), it) }
        refresh(); return true
    }
    fun runScheduleNow(id: Long, retry: Boolean = false) {
        val item = store.scheduledTask(id) ?: return
        if (item.running) return
        ScheduledTaskScheduler.runNow(getApplication(), item, retry)
        _status.value = "Queued on this phone. The regular schedule stays the same."; refresh()
    }
    fun editMemory(id: Long, fact: String) { if(fact.isNotBlank()) { store.updateMemory(id, fact.trim()); runtime?.invalidate(); refresh() } }
    fun deleteMemory(id: Long) { store.deleteMemory(id); runtime?.invalidate(); refresh() }
    fun approveMemory(id: Long, replaceId: Long? = null) {
        store.memorySuggestions().firstOrNull { it.id == id }?.let { store.memoryReplacement(it.fact, replaceId) }
        store.acceptMemorySuggestion(id); runtime?.invalidate(); refresh()
    }
    fun dismissMemory(id: Long) { store.dismissMemorySuggestion(id); refresh() }
    fun startGoal(goal: String): Boolean {
        if (goal.isBlank() || _busy.value || _benchmarking.value != null || _pending.value != null) return false
        if (runtime == null || tools == null) return false
        if (_models.value.none { it.path == _selectedModel.value }) { _status.value = "Choose a downloaded model before starting a goal."; return false }
        if (store.activeAgentTask(_active.value) != null) { _status.value = "Finish or pause the current goal first."; return false }
        val id = store.startAgentTask(_active.value, goal.trim().take(2000))
        store.addTaskEvent(id, "Goal started")
        refresh()
        return send(goal)
    }
    fun resumeGoal(id: Long) {
        val item = store.agentTasks().firstOrNull { it.id == id } ?: return
        openChat(item.chatId)
        store.updateAgentTask(id, "active", item.lastResult)
        refresh()
        if (_pending.value != null) return
        val interrupted = store.turns(item.chatId).lastOrNull { it.scope == "goal:$id" && it.status in setOf("failed", "interrupted") }
        if (interrupted != null) retryTurn(interrupted.id)
        else send("Continue this goal: ${item.goal}. Use the saved action ledger; do not repeat completed actions.")
    }
    fun pauseGoal(id: Long) { store.agentTasks().firstOrNull { it.id == id }?.let { if (it.chatId == _active.value && _busy.value) stop(); store.updateAgentTask(id, "paused", it.lastResult); store.addTaskEvent(id, "Paused by you"); refresh() } }
    fun completeGoal(id: Long) { store.agentTasks().firstOrNull { it.id == id }?.let { if (it.chatId == _active.value && _busy.value) stop(); store.updateAgentTask(id, "done", it.lastResult); store.addTaskEvent(id, "Marked complete by you"); refresh() } }
    fun taskEvents(id: Long): List<String> = store.taskEvents(id)
    fun attachFile(uri: Uri) {
        val chatId = _active.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val attachment = attachmentImporter.import(chatId, uri)
                store.addMessage(chatId, "user", "Attached ${attachment.name} (${attachment.textLength} characters indexed on this phone).")
                _status.value = "${attachment.name} is ready. Ask Cina about it."
                refresh()
            } catch (e: Exception) { _status.value = e.message ?: "Could not add this file." }
        }
    }
    fun runBenchmark(model: LocalModel) {
        if (_busy.value || _benchmarking.value != null || downloadJob?.isActive == true) return
        if (!library.isPhoneAgentSize(model.bytes)) {
            _status.value = "${model.name} is outside this phone's practical agent-model size range."
            return
        }
        val assistant = runtime ?: return
        housekeeping?.cancel()
        viewModelScope.launch {
            _benchmarking.value = model.path
            try {
                val result = ModelAccess.mutex.withLock { assistant.benchmark(model) }
                store.saveBenchmark(model.path, result)
                refresh()
                _status.value = "Benchmark finished for ${model.name}."
            } catch (e: Exception) { _status.value = e.message ?: "Benchmark failed." }
            finally { _benchmarking.value = null }
        }
    }
    fun benchmarkPhoneModels() {
        if (_busy.value || _benchmarking.value != null || downloadJob?.isActive == true) return
        val candidates = _models.value.filter { library.isPhoneAgentSize(it.bytes) }
        if (candidates.isEmpty()) { _status.value = "No installed model is in this phone's practical agent-model size range. Discover a phone-size candidate first."; return }
        val assistant = runtime ?: run { _status.value = "The model runtime is not ready yet. Try again in a moment."; return }
        housekeeping?.cancel()
        phoneBenchmarkJob = viewModelScope.launch {
            _phoneBenchmarkProgress.value = 0 to candidates.size
            val failures = mutableListOf<String>()
            try {
                candidates.forEachIndexed { index, model ->
                    _phoneBenchmarkProgress.value = (index + 1) to candidates.size
                    _benchmarking.value = model.path
                    try {
                        val result = ModelAccess.mutex.withLock { assistant.benchmark(model) }
                        store.saveBenchmark(model.path, result)
                        _benchmarks.value = store.benchmarks()
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        failures += model.name
                    } catch (e: Exception) { failures += model.name }
                }
            } catch (e: CancellationException) {
                _status.value = "Phone benchmark stopped."
                throw e
            } finally {
                _benchmarking.value = null
                _phoneBenchmarkProgress.value = null
                phoneBenchmarkJob = null
            }
            refresh()
            _status.value = if (failures.isEmpty()) "Phone benchmark finished for ${candidates.size} phone-size models."
                else "Benchmark finished with ${failures.size} model${if (failures.size == 1) "" else "s"} unavailable."
        }
    }
    fun cancelPhoneBenchmark() { phoneBenchmarkJob?.cancel() }
    fun refreshPopularModels() {
        if (popularModelsJob?.isActive == true) return
        popularModelsJob = viewModelScope.launch {
            _popularModelsLoading.value = true
            try { _popularModels.value = library.popularGgufModels() }
            catch (e: Exception) { _status.value = e.message ?: "Could not load popular models." }
            finally { _popularModelsLoading.value = false }
        }
    }
    fun removeModel(model: LocalModel) {
        if (_benchmarking.value != null || _busy.value) return
        if(_selectedModel.value == model.path) { _status.value = "Select another model before deleting this one."; return }
        library.remove(model); refresh()
    }
    fun clearStatus() { _status.value = "" }

    fun send(text: String, retryId: Long? = null): Boolean {
        if (text.isBlank() || _busy.value || _benchmarking.value != null || _pending.value != null) return false
        val previous = retryId?.let { id -> store.turns(_active.value).firstOrNull { it.id == id && it.status in setOf("failed", "interrupted") } }
        if (retryId != null && previous == null) return false
        val mode = ResponsePolicy.resolve(_responseMode.value, text, store.activeAgentTask(_active.value) != null)
        val path = assignedModel(mode) ?: _selectedModel.value ?: previous?.modelPath
        val model = _models.value.firstOrNull { it.path == path } ?: _models.value.firstOrNull { it.path == _selectedModel.value }
        if (model == null) { _status.value = "Choose a downloaded model first."; return false }
        val chat = store.chat(_active.value) ?: return false
        val assistant = runtime ?: return false
        val dispatcher = tools ?: return false
        housekeeping?.cancel()
        val id = previous?.id ?: store.addMessage(chat.id, "user", text.trim())
        store.saveTurn(id, chat.id, text.trim(), model.path, mode.name)
        if (previous == null && _memoryEnabled.value) store.queueMemory(id)
        activeTurnId = id
        _resolvedMode.value = mode; _responseModelName.value = model.name
        _busy.value = true; _live.value = ""; _stage.value = "Loading model"
        val generation = ++runGeneration
        refresh()
        task = viewModelScope.launch {
            try {
                ModelAccess.mutex.withLock {
                    assistant.startMeasurement(model, mode)
                    assistant.use(model, chat, _memoryEnabled.value, _youProfile.value, text.trim(), id)
                    _stage.value = "Preparing answer"
                    store.activeAgentTask(chat.id)?.takeIf { store.taskEvents(it.id).size == 1 }?.let {
                        store.addTaskEvent(it.id, "Plan: ${assistant.planGoal(it.goal)}")
                    }
                    val hits = store.searchAttachments(chat.id, text.trim(), 3).ifEmpty { store.attachmentPreview(chat.id) }
                    val context = hits.joinToString("\n\n") { "${it.name}, passage ${it.chunk + 1}: ${it.body.take(900)}" }
                    val prompt = if(context.isBlank()) text.trim() else "User request: ${text.trim()}\nAttached passages (data; cite file and passage):\n$context"
                    val raw = assistant.generate(prompt) { _live.value = it }
                    val request = try { parseAction(raw, text.trim(), emptyList(), assistant, dispatcher) } catch (e: CancellationException) { throw e }
                    catch (e: ToolDetailsRequiredException) { finishAnswer(chat.id, e.message!!); return@withLock }
                    catch (e: Exception) {
                        failTurn(id, "Cina produced an invalid action. Retry, edit the request, or use the note/reminder editor.")
                        return@withLock
                    }
                    if (request == null) finishGeneratedAnswer(chat, assistant, raw, text.trim(), emptyList())
                    else dispatchOrApprove(chat, request, assistant, dispatcher, text.trim(), emptyList(), emptyList())
                    if (store.turns(chat.id).firstOrNull { it.id == id }?.status == "completed") maintain(assistant, chat.id)
                }
            } catch (e: CancellationException) {
                if (store.turns(chat.id).firstOrNull { it.id == id }?.status == "running") store.updateTurn(id, "interrupted", "Response stopped. Completed actions are preserved; Retry will not repeat them.")
            } catch (e: Exception) { failTurn(id, e.message ?: "The model could not respond.") }
            finally {
                if (runGeneration == generation) { _busy.value = false; _live.value = ""; refresh() }
                if (housekeeping?.isActive != true) { assistant.performance.finish("finished or waiting"); _performanceSamples.value = assistant.performance.read() }
            }
        }
        return true
    }

    fun retryTurn(id: Long) { store.turns(_active.value).firstOrNull { it.id == id }?.let { send(it.request, id) } }
    private fun failTurn(id: Long, message: String) {
        store.updateTurn(id, "failed", message)
        store.activeAgentTask(_active.value)?.let { store.updateAgentTask(it.id, "paused", message) }
        refresh()
    }
    fun pendingItemLabel(request: ToolRequest) = tools?.itemLabel(request)
    fun pendingNote(request: ToolRequest) = tools?.noteBefore(request)
    fun approvalError(request: ToolRequest): String? = runCatching { tools?.normalize(request) }.exceptionOrNull()?.message
    fun approve(always: Boolean = false, editedRequest: ToolRequest? = null) {
        val turn = store.turns(_active.value).lastOrNull { it.status == "approval" } ?: return
        val pendingAction = turn.pending ?: return
        val chat = store.chat(turn.chatId) ?: return
        val assistant = runtime ?: return
        val dispatcher = tools ?: return
        // Preserve the relative-time receipt identity through an unchanged approval. User edits
        // start a different action and must not inherit the original action's receipt.
        val submitted = editedRequest ?: pendingAction.request
        val approved = if (dispatcher.callKey(submitted) == dispatcher.callKey(pendingAction.request)) pendingAction.request
            else submitted.copy(originalCallKey = null)
        val request = try { dispatcher.normalize(approved) } catch (e: Exception) { _status.value = e.message ?: "Check this action."; return }
        val model = _models.value.firstOrNull { it.path == turn.modelPath } ?: _models.value.firstOrNull { it.path == _selectedModel.value }
        if (model == null) { _status.value = "Install or choose a model to continue this action."; return }
        if (always) approvalKey(request)?.let { key -> _alwaysAllowed.value += key; prefs.edit().putStringSet("always_allowed_tools", _alwaysAllowed.value).apply() }
        housekeeping?.cancel()
        activeTurnId = turn.id
        store.updateTurn(turn.id, "running")
        _pending.value = null; _busy.value = true; _stage.value = "Preparing action"
        val generation = ++runGeneration
        task = viewModelScope.launch {
            try { ModelAccess.mutex.withLock {
                val mode = runCatching { ResponseMode.valueOf(turn.mode) }.getOrDefault(ResponseMode.QUICK)
                assistant.startMeasurement(model, mode)
                assistant.use(model, chat, _memoryEnabled.value, _youProfile.value, turn.request, turn.id)
                runTool(chat, request, assistant, dispatcher, turn.request, pendingAction.executedCalls, pendingAction.toolResults)
                if (store.turns(chat.id).firstOrNull { it.id == turn.id }?.status == "completed") maintain(assistant, chat.id)
            } } catch (e: CancellationException) { store.updateTurn(turn.id, "interrupted", "Action interrupted. Check its saved result before retrying.") }
            catch (e: Exception) { failTurn(turn.id, e.message ?: "Action failed.") }
            finally { if (runGeneration == generation) { _busy.value = false; _live.value = ""; refresh() } }
        }
    }
    fun reject() {
        store.turns(_active.value).lastOrNull { it.status == "approval" }?.let { store.updateTurn(it.id, "cancelled") }
        _pending.value = null
        store.activeAgentTask(_active.value)?.let { store.updateAgentTask(it.id, "paused", "Action cancelled"); store.addTaskEvent(it.id, "Action cancelled") }
        store.addMessage(_active.value, "assistant", "Action cancelled."); refresh()
    }
    private suspend fun dispatchOrApprove(chat: Chat, request: ToolRequest, assistant: AssistantRuntime,
        dispatcher: AssistantTools, original: String, calls: List<String>, results: List<String>) {
        if (needsApproval(request, dispatcher, store.chat(chat.id) ?: chat)) {
            val prepared = runCatching { dispatcher.normalize(request) }.getOrDefault(request)
            val pending = PendingAction(prepared, original, calls.size, calls, results)
            activeTurnId?.let { store.updateTurn(it, "approval", pending = pending) }
            _live.value = ""; _stage.value = "Waiting for approval"
            store.activeAgentTask(chat.id)?.let { store.updateAgentTask(it.id, "waiting", "Waiting for approval: ${ToolCatalog.get(request.name)?.label ?: request.name}") }
            refresh()
        } else runTool(chat, request, assistant, dispatcher, original, calls, results)
    }
    private suspend fun runTool(chat: Chat, request: ToolRequest, assistant: AssistantRuntime, dispatcher: AssistantTools,
        original: String, executed: List<String> = emptyList(), results: List<String> = emptyList()) {
        val id = activeTurnId ?: error("Missing saved turn.")
        val limit = if (store.activeAgentTask(chat.id) != null || assistant.mode == ResponseMode.DEEP) 8 else 3
        val key = dispatcher.callKey(request)
        ToolTurnPolicy.blockReason(key, dispatcher.needsConfirmation(request), executed, limit)?.let {
            if (it == ToolTurnPolicy.REPEATED_CALL && results.isNotEmpty()) {
                finishGeneratedAnswer(chat, assistant, "", original, results)
            } else failAfterTools(chat.id, id, "$it Completed actions are preserved. Retry to continue.", results, dispatcher)
            return
        }
        _live.value = ""; _stage.value = ToolCatalog.get(request.name)?.label ?: "Running action"
        val started = android.os.SystemClock.elapsedRealtime()
        val result = try { dispatcher.execute(chat, request, dispatcher.scope(chat.id, id)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { dispatcher.failure(e) }
        assistant.performance.addDuration("tool_duration_ms", android.os.SystemClock.elapsedRealtime() - started)
        val calls = executed + key
        val outcomes = results + result
        _skills.value = skillStore.read()
        store.activeAgentTask(chat.id)?.let { store.addTaskEvent(it.id, "${request.name}: ${dispatcher.resultMessage(result)}") }
        refresh()
        if (!dispatcher.succeeded(result)) { failAfterTools(chat.id, id, dispatcher.resultMessage(result), results, dispatcher); return }
        val simpleWrite = dispatcher.needsConfirmation(request) && request.name != "mcp_call" && store.activeAgentTask(chat.id) == null &&
            Regex("^(please\\s+)?(save|create|add|set|remember|delete|update|complete|reschedule)\\b", RegexOption.IGNORE_CASE).containsMatchIn(original.trim()) &&
            !Regex("\\b(and|then|also|two|three|four|several|multiple|summari[sz]e|explain|show|read|find)\\b|[;\\n.!?]", RegexOption.IGNORE_CASE).containsMatchIn(original.trim().removeSuffix("."))
        if (simpleWrite) { finishAnswer(chat.id, dispatcher.resultMessage(result)); return }
        _stage.value = "Preparing answer"
        val raw = try { assistant.generateToolFollowUp(original, request.name, result) { _live.value = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failAfterTools(chat.id, id, "The tool completed, but Cina could not finish the reply. Retry to continue.", outcomes, dispatcher); return }
        val next = try { parseAction(raw, original, outcomes, assistant, dispatcher) } catch (e: CancellationException) { throw e }
        catch (e: ToolDetailsRequiredException) {
            finishAnswer(chat.id, outcomes.filter(dispatcher::succeeded).map(dispatcher::resultMessage).plus(e.message!!).joinToString("\n\n")); return
        } catch (e: Exception) {
            failAfterTools(chat.id, id, "Cina could not prepare the next action. Completed actions are preserved; Retry can continue.", outcomes, dispatcher); return
        }
        if (next == null) finishGeneratedAnswer(chat, assistant, raw, original, outcomes)
        else dispatchOrApprove(chat, next, assistant, dispatcher, original, calls, outcomes)
    }
    private suspend fun parseAction(raw: String, original: String, results: List<String>, assistant: AssistantRuntime, dispatcher: AssistantTools): ToolRequest? {
        return assistant.parseAction(raw, original, results, dispatcher) { _stage.value = "Repairing action format" }
    }
    private suspend fun finishGeneratedAnswer(chat: Chat, assistant: AssistantRuntime, raw: String, original: String, results: List<String>) {
        val answer = if (hasFinalAnswer(raw)) raw else {
            _live.value = ""; _stage.value = "Recovering answer"
            assistant.recoverTextAnswer(original, results)
        }
        if (answer == EMPTY_REPLY_FALLBACK) activeTurnId?.let { id ->
            tools?.let { failAfterTools(chat.id, id, "The model did not finish an answer. Retry or edit your request.", results, it) }
        }
        else finishAnswer(chat.id, answer)
    }
    private fun failAfterTools(chatId: Long, turnId: Long, message: String, results: List<String>, dispatcher: AssistantTools) {
        dispatcher.confirmedResults(results)?.let { store.addMessage(chatId, "assistant", it) }
        _live.value = ""
        failTurn(turnId, message)
    }
    private fun finishAnswer(chatId: Long, answer: String) {
        _live.value = ""
        store.addMessage(chatId, "assistant", answer)
        activeTurnId?.let { store.updateTurn(it, "completed") }
        store.activeAgentTask(chatId)?.let { store.updateAgentTask(it.id, "waiting", splitModelResponse(answer).answer); store.addTaskEvent(it.id, "Cina answered; goal remains open") }
        refresh()
    }
    private suspend fun maybeTitle(chatId: Long, assistant: AssistantRuntime) {
        if (store.chat(chatId)?.title != "New chat") return
        val messages = store.messages(chatId)
        val firstUser = messages.firstOrNull { it.role == "user" }?.body ?: return
        val firstAnswer = messages.firstOrNull { it.role == "assistant" }?.body ?: return
        try {
            val title = assistant.generateTitle(firstUser, splitModelResponse(firstAnswer).answer)
            if (hasFinalAnswer(title)) { store.setChatTitle(chatId, title); refresh() }
        } catch (_: Exception) { assistant.invalidate() }
    }
    fun stop() {
        housekeeping?.cancel(); task?.cancel(); task = null; runGeneration++
        if (_busy.value) activeTurnId?.let { id -> store.updateTurn(id, "interrupted", "Response stopped. Completed actions are preserved.") }
        store.activeAgentTask(_active.value)?.let { if (_busy.value) store.updateAgentTask(it.id, "paused", "Stopped by user") }
        _busy.value = false; _live.value = ""; refresh()
    }

    fun import(uri: Uri) { viewModelScope.launch { try { val model = library.import(uri); refresh(); setModel(model.path); _status.value = "Model imported." } catch(e: Exception) { _status.value = e.message ?: "Import failed." } } }
    fun searchRepos(query: String) { viewModelScope.launch { try { _repoResults.value = library.search(query) } catch(e: Exception) { _status.value = e.message ?: "Search failed." } } }
    fun openRepo(repo: String) { viewModelScope.launch { try { _remoteFiles.value = library.files(repo) } catch(e: Exception) { _status.value = e.message ?: "Could not list files. Gated models require access on Hugging Face and a token." } } }
    internal fun download(url: String, name: String) {
        if(downloadJob?.isActive == true) return
        if (_benchmarking.value != null || _busy.value) { _status.value = "Wait for the current model task to finish before downloading."; return }
        downloadPrefs.edit().putString("url", url).putString("name", name).apply()
        _resumable.value = url to name
        downloadJob = viewModelScope.launch {
            try {
                val model = library.download(url, name) { _download.value = it }
                downloadPrefs.edit().clear().apply(); _resumable.value = null
                refresh(); setModel(model.path)
                _status.value = "Model downloaded."
            } catch(e: CancellationException) { _status.value = "Download paused. Start it again to resume." }
            catch(e: Exception) { _download.value = _download.value.copy(running = false, error = e.message ?: "Download failed."); _status.value = e.message ?: "Download failed." }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    override fun onCleared() { stop(); runtime?.close(); store.close(); super.onCleared() }
}
