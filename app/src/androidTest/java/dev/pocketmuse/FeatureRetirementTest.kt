package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class FeatureRetirementTest {
    @Test fun savedGoalHistoryIsPreservedButCannotDriveNewTurns() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        LocalStore(context).use { store ->
            val chat=store.newChat()
            try {
                val goal=store.startAgentTask(chat,"Legacy goal history")
                store.addTaskEvent(goal,"Previously saved progress")
                assertNull(store.activeAgentTask(chat))
                val id=store.addMessage(chat,"user","A new ordinary request")
                store.saveTurn(id,chat,"A new ordinary request","fixture","QUICK")
                assertEquals("turn:$id",store.turns(chat).last().scope)
                assertTrue(store.agentTasks().any { it.id==goal })
                assertTrue(store.taskEvents(goal).contains("Previously saved progress"))
            } finally { store.deleteChat(chat,false) }
        }
    }
}
