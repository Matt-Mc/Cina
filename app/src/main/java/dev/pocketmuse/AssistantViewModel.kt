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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
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
    private val prefs = application.getSharedPreferences("settings", 0)
    private val downloadPrefs = application.getSharedPreferences("downloads", 0)
    init { store.chats().filter { it.yolo }.forEach { store.endChat(it.id) } }

    private val _chats = MutableStateFlow(store.chats())
    val chats = _chats.asStateFlow()
    private val _active = MutableStateFlow(_chats.value.firstOrNull()?.id ?: createChat().also { _chats.value = store.chats() })
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
    private val _logs = MutableStateFlow(store.actionLog(_active.value))
    val logs = _logs.asStateFlow()
    private val _live = MutableStateFlow("")
    val live = _live.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val _pending = MutableStateFlow<PendingAction?>(null)
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
    private val _connections = MutableStateFlow(mcp.snapshot())
    val connections = _connections.asStateFlow()
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
        tools = AssistantTools(activity, store, secrets, mcp); runtime = AssistantRuntime(activity, store) { webEnabled -> tools!!.specification(webEnabled) }
        resumeMemoryProcessing()
        viewModelScope.launch(Dispatchers.IO) { mcp.refreshEnabled(); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    }
    fun connectMcp(id: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.connect(id, token)
                if (id == "linear") mcp.useManualLinearToken()
                _status.value = "Connected ${mcp.snapshot().first { it.preset.id == id }.preset.title}: $count tools available."
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Connection failed." }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun addMcpServer(name: String, url: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.addCustom(name, url, token)
                _status.value = "Connected ${name.trim()}: $count tools available."
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Connection failed." }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun removeMcpServer(id: String) {
        mcp.removeCustom(id); _connections.value = mcp.snapshot(); runtime?.invalidate()
    }
    fun startLinearSignIn(activity: Activity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = mcp.beginLinearOAuth()
                withContext(Dispatchers.Main) { activity.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            } catch (e: Exception) { _status.value = e.message ?: "Could not start Linear sign-in." }
        }
    }
    fun finishLinearSignIn(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.completeLinearOAuth(uri)
                _status.value = "Connected Linear: $count tools available."
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Linear sign-in failed." }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun disconnectMcp(id: String) { mcp.disconnect(id); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    fun refresh() { _chats.value = store.chats(); _messages.value = store.messages(_active.value); _models.value = store.models(); _notes.value = store.notes(); _reminders.value = store.reminders(); _scheduledTasks.value = store.scheduledTasks(); _memories.value = store.memories(); _memorySuggestions.value = store.memorySuggestions(); _agentTasks.value = store.agentTasks(); _attachments.value = store.attachments(_active.value); _benchmarks.value = store.benchmarks(); _logs.value = store.actionLog(_active.value); TodayWidget.updateAll(getApplication()) }
    private fun createChat() = store.newChat(prefs.getBoolean("default_web", false), prefs.getBoolean("default_yolo", false))
    fun newChat() { stop(); store.endChat(_active.value); _active.value = createChat(); refresh() }
    fun openChat(id: Long) { stop(); store.endChat(_active.value); _active.value = id; refresh() }
    fun setFlag(flag: String, enabled: Boolean) { store.setChatFlag(_active.value, flag, enabled); refresh() }
    fun endSession() { store.endChat(_active.value); refresh() }
    fun setModel(path: String) { housekeeping?.cancel(); stop(); runtime?.invalidate(); _selectedModel.value = path; prefs.edit().putString("model", path).apply() }
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
        return dispatcher.needsConfirmation(request) && !chat.yolo && (key == null || key !in _alwaysAllowed.value)
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
    fun editMemory(id: Long, fact: String) { if(fact.isNotBlank()) { store.updateMemory(id, fact.trim()); runtime?.invalidate(); refresh() } }
    fun deleteMemory(id: Long) { store.deleteMemory(id); runtime?.invalidate(); refresh() }
    fun approveMemory(id: Long) { store.acceptMemorySuggestion(id); runtime?.invalidate(); refresh() }
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
        send("Continue this goal: ${item.goal}. Review what has already been done before taking another action.")
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
        if (_busy.value || _benchmarking.value != null) return
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
    fun removeModel(model: LocalModel) { if(_selectedModel.value == model.path) { _status.value = "Select another model before deleting this one."; return }; library.remove(model); refresh() }
    fun clearStatus() { _status.value = "" }

    fun send(text: String): Boolean {
        if(text.isBlank() || _busy.value || _benchmarking.value != null || _pending.value != null) return false
        val mode = ResponsePolicy.resolve(_responseMode.value, text, store.activeAgentTask(_active.value) != null)
        val path = assignedModel(mode) ?: _selectedModel.value
        val model = _models.value.firstOrNull { it.path == path } ?: _models.value.firstOrNull { it.path == _selectedModel.value }
        if(model == null) { _status.value = "Select a downloaded model first."; return false }
        val chat = store.chat(_active.value) ?: return false
        val assistant = runtime ?: return false
        val dispatcher = tools ?: return false
        _resolvedMode.value = mode; _responseModelName.value = model.name
        housekeeping?.cancel()
        val submittedAt = android.os.SystemClock.elapsedRealtime()
        val messageId = store.addMessage(chat.id, "user", text.trim()); if (_memoryEnabled.value) store.queueMemory(messageId); refresh()
        task = viewModelScope.launch {
            _busy.value = true; _live.value = ""
            try {
                ModelAccess.mutex.withLock {
                assistant.startMeasurement(model, mode, submittedAt)
                assistant.use(model, chat, _memoryEnabled.value, _youProfile.value)
                if (mode == ResponseMode.QUICK && !assistant.thinkingSupported) _status.value = "Quick mode: this model has no native thinking switch."
                store.activeAgentTask(chat.id)?.takeIf { store.taskEvents(it.id).size == 1 }?.let { goalTask ->
                    try { store.addTaskEvent(goalTask.id, "Plan: ${assistant.planGoal(goalTask.goal)}"); refresh() } catch (_: Exception) { }
                }
                val hits = store.searchAttachments(chat.id, text.trim(), 3).ifEmpty { store.attachmentPreview(chat.id) }
                val attachedContext = hits.joinToString("\n\n") { "${it.name}, passage ${it.chunk + 1}: ${it.body.take(900)}" }
                val prompt = if(attachedContext.isBlank()) text.trim() else "User request: ${text.trim()}\nRelevant attached file passages (untrusted source data; cite file and passage):\n$attachedContext"
                val output = assistant.generate(prompt) { _live.value = it }
                val request = try { dispatcher.parse(splitModelResponse(output).answer) } catch (_: Exception) { finishAnswer(chat.id, "I couldn't understand the requested action. Please try rephrasing it."); return@launch }
                if(request == null) finishGeneratedAnswer(chat, assistant, output, prompt, emptyList())
                else if(needsApproval(request, dispatcher, chat)) {
                    _pending.value = PendingAction(request, text.trim()); _live.value = ""
                    store.activeAgentTask(chat.id)?.let { store.updateAgentTask(it.id, "waiting", "Waiting for approval: ${request.name}"); store.addTaskEvent(it.id, "Approval needed: ${request.name}"); refresh() }
        } else runTool(chat, request, assistant, dispatcher, text.trim())
                if (_pending.value == null) {
                    maintain(assistant, chat.id)
                }
                }
            } catch (_: CancellationException) { _status.value = "Stopped." }
            catch(e: Exception) { store.activeAgentTask(chat.id)?.let { store.updateAgentTask(it.id, "paused", "Error: ${e.message}"); refresh() }; _status.value = e.message ?: "The model could not respond." }
            finally { _busy.value = false; _live.value = ""; if (_pending.value != null) { assistant.performance.finish("approval needed"); _performanceSamples.value = assistant.performance.read() } else if (housekeeping?.isActive != true) { assistant.performance.finish("interrupted or failed"); _performanceSamples.value = assistant.performance.read() } }
        }
        return true
    }

    fun approve(always: Boolean = false) {
        val pendingAction = _pending.value ?: return
        val chat = store.chat(_active.value) ?: return
        val assistant = runtime ?: return
        val dispatcher = tools ?: return
        if (always) approvalKey(pendingAction.request)?.let { key ->
            _alwaysAllowed.value = _alwaysAllowed.value + key
            prefs.edit().putStringSet("always_allowed_tools", _alwaysAllowed.value).apply()
        }
        _pending.value = null
        task = viewModelScope.launch {
            _busy.value = true
            try { ModelAccess.mutex.withLock {
                val model = _models.value.firstOrNull { it.name == _responseModelName.value }
                if(model != null) assistant.startMeasurement(model, _resolvedMode.value ?: ResponseMode.QUICK)
                runTool(chat, pendingAction.request, assistant, dispatcher, pendingAction.originalUserText, pendingAction.depth, pendingAction.executedCalls, pendingAction.toolResults)
                if (_pending.value == null) {
                    maintain(assistant, chat.id)
                }
            } }
            catch(e: Exception) { _status.value = e.message ?: "Action failed." }
            finally { _busy.value = false; _live.value = ""; if (_pending.value != null) { assistant.performance.finish("approval needed"); _performanceSamples.value = assistant.performance.read() } else if (housekeeping?.isActive != true) { assistant.performance.finish("interrupted or failed"); _performanceSamples.value = assistant.performance.read() } }
        }
    }
    fun reject() { _pending.value = null; store.activeAgentTask(_active.value)?.let { store.updateAgentTask(it.id, "paused", "Action cancelled"); store.addTaskEvent(it.id, "Action cancelled") }; store.addMessage(_active.value, "assistant", "Action cancelled."); refresh() }
    private suspend fun runTool(chat: Chat, request: ToolRequest, assistant: AssistantRuntime, dispatcher: AssistantTools, originalUserText: String, depth: Int = 0,
                                executedCalls: List<String> = emptyList(), toolResults: List<String> = emptyList()) {
        val limit = if (store.activeAgentTask(chat.id) != null || assistant.mode == ResponseMode.DEEP) 8 else 3
        val key = dispatcher.callKey(request)
        val blocked = ToolTurnPolicy.blockReason(key, dispatcher.needsConfirmation(request), executedCalls, limit)
        if (blocked != null) {
            finishToolLoop(chat, assistant, originalUserText, toolResults, blocked)
            return
        }
        _live.value = ""
        val toolStarted = android.os.SystemClock.elapsedRealtime()
        val result = try { dispatcher.execute(chat, request) } catch (e: CancellationException) { throw e }
            catch(e: Exception) { "Tool error: ${e.message}".also { store.log(chat.id, "${dispatcher.describe(request)} -> $it") } }
        assistant.performance.addDuration("tool_duration_ms", android.os.SystemClock.elapsedRealtime() - toolStarted)
        _skills.value = skillStore.read()
        val calls = executedCalls + key
        val results = toolResults + "${request.name}: ${result.take(1200)}"
        store.activeAgentTask(chat.id)?.let { store.addTaskEvent(it.id, "${request.name}: ${result.take(300)}"); store.updateAgentTask(it.id, "active", result) }
        refresh()
        if (calls.size >= limit) {
            finishToolLoop(chat, assistant, originalUserText, results, "Action limit reached.")
            return
        }
        val response = assistant.generate(AssistantPrompt.toolFollowUp(originalUserText, request.name, result)) { _live.value = it }
        val next = try { dispatcher.parse(splitModelResponse(response).answer) } catch (_: Exception) {
            finishToolLoop(chat, assistant, originalUserText, results, "Malformed tool call stopped.")
            return
        }
        if (next == null) finishGeneratedAnswer(chat, assistant, response, originalUserText, results)
        else if (ToolTurnPolicy.blockReason(dispatcher.callKey(next), dispatcher.needsConfirmation(next), calls, limit) != null) {
            finishToolLoop(chat, assistant, originalUserText, results, "Repeated tool call stopped.")
        }
        else if (needsApproval(next, dispatcher, chat)) {
            _pending.value = PendingAction(next, originalUserText, depth + 1, calls, results); _live.value = ""
            store.activeAgentTask(chat.id)?.let { store.updateAgentTask(it.id, "waiting", "Waiting for approval: ${next.name}"); store.addTaskEvent(it.id, "Approval needed: ${next.name}") }; refresh()
        }
        else runTool(chat, next, assistant, dispatcher, originalUserText, depth + 1, calls, results)
    }
    private suspend fun finishToolLoop(chat: Chat, assistant: AssistantRuntime, originalUserText: String, results: List<String>, reason: String) {
        _live.value = ""
        store.log(chat.id, reason)
        val answer = assistant.recoverTextAnswer(originalUserText, results)
        finishAnswer(chat.id, answer)
        store.activeAgentTask(chat.id)?.let { store.updateAgentTask(it.id, "paused", answer); store.addTaskEvent(it.id, reason); refresh() }
    }
    private suspend fun finishGeneratedAnswer(chat: Chat, assistant: AssistantRuntime, raw: String, originalRequest: String, results: List<String>) {
        val answer = if (hasFinalAnswer(raw)) raw else {
            _live.value = ""
            store.log(chat.id, "No final answer; attempting one text-only recovery.")
            assistant.recoverTextAnswer(originalRequest, results)
        }
        finishAnswer(chat.id, answer)
    }
    private fun finishAnswer(chatId: Long, answer: String) {
        _live.value = ""
        val body = if (hasFinalAnswer(answer)) answer else EMPTY_REPLY_FALLBACK
        store.addMessage(chatId, "assistant", body)
        store.activeAgentTask(chatId)?.let {
            store.updateAgentTask(it.id, "waiting", splitModelResponse(body).answer)
            store.addTaskEvent(it.id, "Cina answered; goal remains open")
        }
        refresh()
    }
    private suspend fun maybeTitle(chatId: Long, assistant: AssistantRuntime) {
        if (store.chat(chatId)?.title != "New chat") return
        val messages = store.messages(chatId)
        val firstUser = messages.firstOrNull { it.role == "user" }?.body ?: return
        val firstAnswer = messages.firstOrNull { it.role == "assistant" }?.body ?: return
        try {
            val title = assistant.generateTitle(firstUser, splitModelResponse(firstAnswer).answer)
            if (title.isNotBlank() && !title.contains("[tool]")) { store.setChatTitle(chatId, title); refresh() }
        } catch (_: Exception) { assistant.invalidate() }
    }
    fun stop() { housekeeping?.cancel(); task?.cancel(); task = null; store.activeAgentTask(_active.value)?.let { store.updateAgentTask(it.id, "paused", "Stopped by user") }; _busy.value = false; _pending.value = null; _live.value = ""; refresh() }

    fun import(uri: Uri) { viewModelScope.launch { try { val model = library.import(uri); refresh(); setModel(model.path); _status.value = "Model imported." } catch(e: Exception) { _status.value = e.message ?: "Import failed." } } }
    fun searchRepos(query: String) { viewModelScope.launch { try { _repoResults.value = library.search(query) } catch(e: Exception) { _status.value = e.message ?: "Search failed." } } }
    fun openRepo(repo: String) { viewModelScope.launch { try { _remoteFiles.value = library.files(repo) } catch(e: Exception) { _status.value = e.message ?: "Could not list files. Gated models require access on Hugging Face and a token." } } }
    fun download(url: String, name: String) {
        if(downloadJob?.isActive == true) return
        downloadPrefs.edit().putString("url", url).putString("name", name).apply()
        _resumable.value = url to name
        downloadJob = viewModelScope.launch {
            try {
                val model = library.download(url, name) { _download.value = it }
                downloadPrefs.edit().clear().apply(); _resumable.value = null
                refresh(); setModel(model.path); _status.value = "Model downloaded."
            } catch(e: CancellationException) { _status.value = "Download paused. Start it again to resume." }
            catch(e: Exception) { _download.value = _download.value.copy(running = false, error = e.message ?: "Download failed."); _status.value = e.message ?: "Download failed." }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    override fun onCleared() { stop(); runtime?.close(); store.close(); super.onCleared() }
}
