package dev.pocketmuse

import kotlinx.coroutines.sync.Mutex

/** The native inference engine is a process-wide singleton. */
internal object ModelAccess {
    val mutex = Mutex()
    var owner: AssistantRuntime? = null
}
