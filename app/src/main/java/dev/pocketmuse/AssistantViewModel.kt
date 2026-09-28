package dev.pocketmuse

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val profileStore = YouProfileStore(application)
    val secrets = SecretStore(application)
    private val mcp = McpConnections(application, secrets)
    val library = ModelLibrary(application, store, secrets)
    private var runtime: AssistantRuntime? = null
    private var tools: AssistantTools? = null
    private var task: Job? = null
    private var downloadJob: Job? = null
    private val prefs = application.getSharedPreferences("settings", 0)
    private val downloadPrefs = application.getSharedPreferences("downloads", 0)
    init { store.chats().filter { it.yolo }.forEach { store.endChat(it.id) } }

    private val _chats = MutableStateFlow(store.chats())
    val chats = _chats.asStateFlow()
    private val _active = MutableStateFlow(_chats.value.firstOrNull()?.id ?: store.newChat().also { _chats.value = store.chats() })
    val active = _active.asStateFlow()
    private val _messages = MutableStateFlow(store.messages(_active.value))
    val messages = _messages.asStateFlow()
    private val _models = MutableStateFlow(store.models())
    val models = _models.asStateFlow()
    private val _notes = MutableStateFlow(store.notes())
    val notes = _notes.asStateFlow()
    private val _reminders = MutableStateFlow(store.reminders())
    val reminders = _reminders.asStateFlow()
    private val _memories = MutableStateFlow(store.memories())
    val memories = _memories.asStateFlow()
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
    private val _memoryEnabled = MutableStateFlow(prefs.getBoolean("memory", true))
    val memoryEnabled = _memoryEnabled.asStateFlow()
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

    fun attach(activity: Activity) {
        tools = AssistantTools(activity, store, secrets, mcp); runtime = AssistantRuntime(activity, store, tools!!)
        viewModelScope.launch(Dispatchers.IO) { mcp.refreshEnabled(); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    }
    fun connectMcp(id: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = mcp.connect(id, token)
                _status.value = "Connected ${mcp.snapshot().first { it.preset.id == id }.preset.title}: $count tools available."
                runtime?.invalidate()
            } catch (e: Exception) { _status.value = e.message ?: "Connection failed." }
            finally { _connections.value = mcp.snapshot() }
        }
    }
    fun disconnectMcp(id: String) { mcp.disconnect(id); _connections.value = mcp.snapshot(); runtime?.invalidate() }
    fun refresh() { _chats.value = store.chats(); _messages.value = store.messages(_active.value); _models.value = store.models(); _notes.value = store.notes(); _reminders.value = store.reminders(); _memories.value = store.memories(); _logs.value = store.actionLog(_active.value) }
    fun newChat() { stop(); store.endChat(_active.value); _active.value = store.newChat(); refresh() }
    fun openChat(id: Long) { stop(); store.endChat(_active.value); _active.value = id; refresh() }
    fun setFlag(flag: String, enabled: Boolean) { store.setChatFlag(_active.value, flag, enabled); refresh() }
    fun endSession() { store.endChat(_active.value); refresh() }
    fun setModel(path: String) { stop(); runtime?.invalidate(); _selectedModel.value = path; prefs.edit().putString("model", path).apply() }
    fun setMemoryEnabled(enabled: Boolean) { _memoryEnabled.value = enabled; runtime?.invalidate(); prefs.edit().putBoolean("memory", enabled).apply() }
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
    fun completeReminder(id: Long) { store.completeReminder(id); refresh() }
    fun editMemory(id: Long, fact: String) { if(fact.isNotBlank()) { store.updateMemory(id, fact.trim()); runtime?.invalidate(); refresh() } }
    fun deleteMemory(id: Long) { store.deleteMemory(id); runtime?.invalidate(); refresh() }
    fun removeModel(model: LocalModel) { if(_selectedModel.value == model.path) { _status.value = "Select another model before deleting this one."; return }; library.remove(model); refresh() }
    fun clearStatus() { _status.value = "" }

    fun send(text: String) {
        if(text.isBlank() || _busy.value || _pending.value != null) return
        val model = _models.value.firstOrNull { it.path == _selectedModel.value }
        if(model == null) { _status.value = "Select a downloaded model first."; return }
        val chat = store.chat(_active.value) ?: return
        store.addMessage(chat.id, "user", text.trim()); refresh()
        val assistant = runtime ?: return
        val dispatcher = tools ?: return
        task = viewModelScope.launch {
            _busy.value = true; _live.value = ""
            try {
                assistant.use(model, chat, _memoryEnabled.value, _youProfile.value)
                val output = assistant.generate(text.trim()) { _live.value = it }
                val request = try { dispatcher.parse(output) } catch (_: Exception) { finishAnswer(chat.id, "I couldn't understand the requested action. Please try rephrasing it."); return@launch }
                if(request == null) finishAnswer(chat.id, output)
                else if(dispatcher.needsConfirmation(request) && !chat.yolo) {
                    _pending.value = PendingAction(request, text.trim()); _live.value = ""
        } else runTool(chat, request, assistant, dispatcher, text.trim())
                if(_memoryEnabled.value && _pending.value == null) { try { assistant.extractMemory(text.trim()); refresh() } catch (_: Exception) { } finally { assistant.invalidate() } }
            } catch (_: CancellationException) { _status.value = "Stopped." }
            catch(e: Exception) { _status.value = e.message ?: "The model could not respond." }
            finally { _busy.value = false; _live.value = "" }
        }
    }

    fun approve() {
        val pendingAction = _pending.value ?: return
        val chat = store.chat(_active.value) ?: return
        val assistant = runtime ?: return
        val dispatcher = tools ?: return
        _pending.value = null
        task = viewModelScope.launch {
            _busy.value = true
            try { runTool(chat, pendingAction.request, assistant, dispatcher, pendingAction.originalUserText, pendingAction.depth); if(_memoryEnabled.value && _pending.value == null) { try { assistant.extractMemory(pendingAction.originalUserText); refresh() } finally { assistant.invalidate() } } }
            catch(e: Exception) { _status.value = e.message ?: "Action failed." }
            finally { _busy.value = false; _live.value = "" }
        }
    }
    fun reject() { _pending.value = null; store.addMessage(_active.value, "assistant", "Action cancelled."); refresh() }
    private suspend fun runTool(chat: Chat, request: ToolRequest, assistant: AssistantRuntime, dispatcher: AssistantTools, originalUserText: String, depth: Int = 0) {
        val result = try { dispatcher.execute(chat, request) } catch(e: Exception) { "Tool error: ${e.message}".also { store.log(chat.id, "${dispatcher.describe(request)} -> $it") } }
        refresh()
        val response = assistant.generate("Tool result for ${request.name}: $result. Continue the user's request. You may call another tool if needed; otherwise explain the outcome. If web sources appear, include their URLs.") { _live.value = it }
        val next = try { dispatcher.parse(response) } catch (_: Exception) { null }
        if (next == null) finishAnswer(chat.id, response)
        else if (depth >= 2) finishAnswer(chat.id, "I reached the action limit for this request. Last result: ${result.take(1000)}")
        else if (dispatcher.needsConfirmation(next) && !chat.yolo) { _pending.value = PendingAction(next, originalUserText, depth + 1); _live.value = "" }
        else runTool(chat, next, assistant, dispatcher, originalUserText, depth + 1)
    }
    private fun finishAnswer(chatId: Long, answer: String) { store.addMessage(chatId, "assistant", answer.ifBlank { "I couldn't produce a response." }); refresh() }
    fun stop() { task?.cancel(); task = null; _busy.value = false; _pending.value = null; _live.value = "" }

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
