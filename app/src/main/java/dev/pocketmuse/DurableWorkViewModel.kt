package dev.pocketmuse

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DurableWorkViewModel(application: Application): AndroidViewModel(application) {
    private val _tasks=MutableStateFlow<List<DurableTask>>(emptyList())
    val tasks=_tasks.asStateFlow()
    private val _message=MutableStateFlow("")
    val message=_message.asStateFlow()
    private val _busy=MutableStateFlow(false)
    val busy=_busy.asStateFlow()
    private val _events=MutableStateFlow<List<WorkEvent>>(emptyList())
    val events=_events.asStateFlow()
    private suspend fun <T> repository(block: (WorkCoordinator)->T): T=withContext(Dispatchers.IO) { LocalStore(getApplication()).use { block(WorkCoordinator(it)) } }
    fun refresh() { viewModelScope.launch { try { _tasks.value=repository { it.tasks() } } catch(e: CancellationException) { throw e } catch(_: Exception) { _message.value="Could not load tasks." } } }
    private fun change(block: suspend ()->Unit) {
        if(_busy.value) return
        _busy.value=true
        viewModelScope.launch { try { block();_tasks.value=repository { it.tasks() };DurableWorkScheduler.recover(getApplication()) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { _message.value=if(e is IllegalArgumentException) e.message.orEmpty() else "Could not update the task. Reopen it and try again." }
            finally { _busy.value=false }
        }
    }
    fun save(action: WorkAction,existing: DurableTask?=null,proposal: IntakeProposal?=null)=change {
        repository { it.save(action,existing,proposal) };_message.value="Task saved. Review it in Tasks → Actions; nothing runs until approved."
    }
    fun savePlan(actions: List<WorkAction>,runAt: Long?,existing: DurableTask?=null,proposal: IntakeProposal?=null)=change {
        repository { it.savePlan(actions,runAt,existing,proposal) };_message.value="Plan saved. Review every remaining step and its start time before approving."
    }
    fun approve(task: DurableTask)=change {
        withContext(Dispatchers.IO) { DurableWorkScheduler.ensureRecovery(getApplication()) }
        val queued=repository { it.approve(task);it.task(task.id)!! }
        _message.value="Approval saved. Android will run the action; it may be delayed."
        DurableWorkScheduler.enqueue(getApplication(),queued)
    }
    fun control(task: DurableTask,command: String)=change { repository { it.control(task,command) };_message.value="Task updated." }
    fun history(task: DurableTask) { _events.value=emptyList();viewModelScope.launch {
        try { _events.value=repository { it.events(task.id) } }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { _message.value="Could not load task history." }
    } }
}
