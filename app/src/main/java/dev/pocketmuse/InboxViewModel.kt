package dev.pocketmuse

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class InboxViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val _items = MutableStateFlow<List<InboxItem>>(emptyList())
    val items = _items.asStateFlow()
    private val _detail = MutableStateFlow<InboxDetail?>(null)
    val detail = _detail.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()
    private var shareStarted = false
    private val _review = MutableStateFlow<IntakeReview?>(null)
    val review = _review.asStateFlow()
    private val _interpreting = MutableStateFlow(false)
    val interpreting = _interpreting.asStateFlow()
    private var interpretationJob: Job? = null
    private suspend fun <T> repository(block: (InboxRepository) -> T): T = withContext(Dispatchers.IO) {
        LocalStore(getApplication()).use { block(InboxRepository(it)) }
    }
    fun refresh() { viewModelScope.launch {
        try { _items.value = repository { it.items() } }
        catch (_: Exception) { _message.value = "Could not read the Inbox. Please try again." }
    } }
    fun open(item: InboxItem) { viewModelScope.launch {
        try {
            val loaded = repository { repo -> repo.items().firstOrNull { it.id == item.id }?.let { repo.detail(it) } to repo.review(item.id) }
            _detail.value = loaded.first; _review.value = loaded.second
        }
        catch (_: Exception) { _message.value = "Could not open this item. Please try again." }
    } }
    fun closeDetail() { _detail.value = null; _review.value = null }
    fun openSource(id: String) {
        closeDetail()
        viewModelScope.launch {
            try {
                val item=repository { it.items().firstOrNull { source -> source.id==id } }
                if(item==null) _message.value="This source no longer exists." else open(item)
            } catch (_: Exception) { _message.value="Could not open the source." }
        }
    }
    fun cancelInterpretation() { interpretationJob?.cancel() }
    fun interpret() {
        val source = _detail.value ?: return
        val revision = _review.value?.revision ?: return
        if (_busy.value || _interpreting.value || _review.value?.proposals?.isNotEmpty() == true) return
        _interpreting.value = true
        _message.value = "Waiting for the local model…"
        interpretationJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LocalStore(getApplication()).use { store ->
                        val path = getApplication<Application>().getSharedPreferences("settings", 0).getString("model", null)
                        val model = store.models().firstOrNull { it.path == path } ?: error("Choose a downloaded model in Settings → Models, then try again.")
                        withTimeout(180_000) { ModelAccess.mutex.withLock {
                            val runtime = AssistantRuntime(getApplication(), store) { _, _, _, _ -> "" }
                            try {
                                _message.value = "Reading captured material on this phone…"
                                val result = runtime.interpretInbox(model, source)
                                InboxRepository(store).saveInterpretation(source.item.id, revision, result, model.name)
                            } finally { runtime.release() }
                        } }
                    }
                }
                _message.value = "Suggestions saved for review. Nothing has been acted on."
            } catch (error: Exception) {
                val failure = when (error) {
                    is TimeoutCancellationException -> "Interpretation timed out. Try again with a smaller source or faster model."
                    is CancellationException -> "Interpretation stopped. Saved source and existing reviews are unchanged."
                    else -> error.message?.take(400) ?: "Could not interpret this source. Try again."
                }
                _message.value = failure
                withContext(NonCancellable) { runCatching { repository { it.interpretationFailed(source.item.id, revision, failure) } } }
            } finally {
                _interpreting.value = false
                if (_detail.value?.item?.id == source.item.id) open(source.item)
                refresh()
            }
        }
    }
    fun updateProposal(proposal: IntakeProposal, text: String, state: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository { it.updateProposal(proposal.intakeId, proposal.id, proposal.revision, text, state) }
                _message.value = "Review saved. No tasks, reminders, or memories were created."
                _detail.value?.item?.takeIf { it.id == proposal.intakeId }?.let(::open)
                refresh()
            } catch (error: Exception) { _message.value = error.message ?: "Could not save this review." }
            finally { _busy.value = false }
        }
    }
    fun delete(id: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { repository { it.delete(id) }; _detail.value = null; _message.value = "Deleted from this phone."; refresh() }
            catch (_: Exception) { _message.value = "Could not delete this item. Please try again." }
            finally { _busy.value = false }
        }
    }
    fun captureText(text: String, onSaved: () -> Unit) = capture(UUID.randomUUID().toString(), { InboxCapture(getApplication()).text(text) }, onSaved)
    fun captureShare(intent: Intent) {
        if (shareStarted) return
        shareStarted = true
        val id = savedState.get<String>("capture_id") ?: UUID.randomUUID().toString().also { savedState["capture_id"] = it }
        capture(id, { InboxCapture(getApplication()).share(intent) }) {}
    }
    private fun capture(id: String, source: () -> CapturedSource, onSaved: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _saved.value = false
        _message.value = "Saving on this phone…"
        viewModelScope.launch {
            try {
                repository { if (!it.contains(id)) it.capture(id, source()) }
                _saved.value = true
                _message.value = "Saved to Inbox on this phone. No actions have been taken."
                onSaved(); refresh()
            } catch (error: Exception) {
                _message.value = if (error is IllegalArgumentException || error is IllegalStateException)
                    error.message ?: "Could not save. Please try again." else "Could not save this item. Please share it again."
            } finally { _busy.value = false }
        }
    }
}
