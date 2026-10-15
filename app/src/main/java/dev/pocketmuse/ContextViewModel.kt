package dev.pocketmuse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContextViewModel(application: Application) : AndroidViewModel(application) {
    private val _projects = MutableStateFlow<List<ContextProject>>(emptyList())
    val projects = _projects.asStateFlow()
    private val _knowledge = MutableStateFlow<List<KnowledgeEntry>>(emptyList())
    val knowledge = _knowledge.asStateFlow()
    private val _hits = MutableStateFlow<List<ContextHit>>(emptyList())
    val hits = _hits.asStateFlow()
    private val _message = MutableStateFlow("")
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _links = MutableStateFlow<Set<String>>(emptySet())
    val links = _links.asStateFlow()
    private val _history = MutableStateFlow<List<KnowledgeRevision>>(emptyList())
    val history = _history.asStateFlow()
    private var selectedSource: String? = null
    private var searchRevision = 0
    private suspend fun <T> repository(block: (ContextRepository) -> T): T = withContext(Dispatchers.IO) {
        LocalStore(getApplication()).use { block(ContextRepository(it)) }
    }
    fun refresh(sourceId: String? = null) {
        selectedSource = sourceId
        viewModelScope.launch { try {
            val data = repository { Triple(it.projects(),it.knowledge(),sourceId?.let(it::sourceProjects).orEmpty()) }
            _projects.value=data.first; _knowledge.value=data.second
            if(selectedSource==sourceId) _links.value=data.third
        } catch (_: Exception) { _message.value="Could not load saved context. Try again." } }
    }
    private fun change(block: (ContextRepository) -> Unit) {
        if(_busy.value) return
        _busy.value=true
        viewModelScope.launch { try {
            repository(block)
            searchRevision++; _hits.value=emptyList(); _history.value=emptyList()
            _message.value="Saved on this phone. Search again to see current results."
            refresh(selectedSource)
        } catch(error: Exception) { _message.value=error.message ?: "Could not save this change." }
        finally { _busy.value=false } }
    }
    fun promote(proposal: IntakeProposal, expiresAt: Long?) = change { it.promote(proposal,expiresAt) }
    fun revise(entry: KnowledgeEntry,text: String,status: String,expiresAt: Long?,reason: String) = change { it.revise(entry,text,status,expiresAt,reason) }
    fun deleteKnowledge(id: String) = change { it.deleteKnowledge(id) }
    fun saveProject(name: String,project: ContextProject? = null) = change { it.saveProject(name,project) }
    fun deleteProject(id: String) = change { it.deleteProject(id) }
    fun link(sourceId: String,projectId: String,linked: Boolean) = change { it.linkSource(sourceId,projectId,linked) }
    fun clearResults() { searchRevision++; _hits.value=emptyList(); _message.value="" }
    fun search(query: String,projectId: String?,onlyKnowledge: Boolean) {
        val request = ++searchRevision
        _hits.value=emptyList()
        viewModelScope.launch { try {
            val found = repository { it.search(query,projectId,onlyKnowledge) }
            if(request==searchRevision) { _hits.value=found; _message.value=if(found.isEmpty()) "No matching current knowledge or source passages." else "${found.size} matches (maximum 30). Reviewed knowledge appears first." }
        } catch(error: Exception) { if(request==searchRevision) _message.value=error.message ?: "Search failed." } }
    }
    fun history(id: String) {
        _history.value=emptyList()
        viewModelScope.launch { try { _history.value=repository { it.history(id) } } catch (_: Exception) { _message.value="Could not read revision history." } }
    }
}
