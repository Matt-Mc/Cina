package dev.pocketmuse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LibraryItem(val kind: String, val id: String, val title: String, val body: String, val sourceId: String? = null) {
    val label get() = when(kind) {
        "note" -> "Note · saved reference"
        "memory" -> "Personal detail · chat memory"
        "knowledge" -> "Reviewed fact · saved reference"
        "project" -> "Project"
        else -> "Source · saved reference"
    }
}

internal class LibraryViewModel(application: Application): AndroidViewModel(application) {
    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items = _items.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    private var request: Job? = null
    fun load(query: String) {
        request?.cancel()
        request = viewModelScope.launch {
            _loading.value = true; _error.value = ""
            try {
                delay(180)
                val result = withContext(Dispatchers.IO) {
                    LocalStore(getApplication()).use { store ->
                        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        fun matches(text: String) = words.all { text.contains(it, ignoreCase = true) }
                        buildList {
                            addAll(store.notes().filter { matches("${it.title} ${it.body}") }.map { LibraryItem("note",it.id.toString(),it.title,it.body) })
                            addAll(store.memories().filter { matches(it.fact) }.map { LibraryItem("memory",it.id.toString(),it.fact,it.fact) })
                            if(BuildConfig.INBOX_ENABLED) {
                                val context = ContextRepository(store)
                                addAll(context.projects().filter { matches(it.name) }.map { LibraryItem("project",it.id,it.name,"Sources grouped in this project.") })
                                if(words.isEmpty()) {
                                    addAll(context.knowledge().filter { it.status == "active" && (it.expiresAt == null || it.expiresAt > System.currentTimeMillis()) }.map { LibraryItem("knowledge",it.id,it.text,it.text,it.sourceId) })
                                    addAll(InboxRepository(store).items().map { LibraryItem("source",it.id,it.title,if(it.state == "needs_review") "Suggestions need your review" else "Saved on this phone",it.id) })
                                } else {
                                    addAll(context.search(query,null,false).map { LibraryItem(if(it.kind == "knowledge") "knowledge" else "source",it.recordId,it.title,it.body,it.sourceId) }.distinctBy { it.kind to (if(it.kind=="knowledge") it.id else it.sourceId ?: it.id) })
                                }
                            }
                        }
                    }
                }
                _items.value = result
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { _error.value = "Could not search your library. Try again." }
            finally { if(currentCoroutineContext().isActive) _loading.value = false }
        }
    }
}
