package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkPlanWorkerTest {
    @Test fun scheduledWorkerWaitsThenVerifiesEveryStepWithoutChat() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val title="Delayed ${UUID.randomUUID()}"
        val start=System.currentTimeMillis()+6000
        val id=LocalStore(base).use { store ->
            val work=WorkCoordinator(store)
            val id=work.savePlan(listOf(WorkAction("note",title,"Step one"),WorkAction("note","$title second","Step two")),start)
            work.approve(work.task(id)!!);DurableWorkScheduler.enqueue(base,work.task(id)!!);id
        }
        try {
            Thread.sleep(1000)
            LocalStore(base).use { store -> assertTrue(store.notes().none { it.title.startsWith(title) }) }
            var done=false;val deadline=System.currentTimeMillis()+30_000
            while(!done && System.currentTimeMillis()<deadline) {
                done=LocalStore(base).use { WorkCoordinator(it).task(id)?.state=="completed" }
                if(!done) Thread.sleep(100)
            }
            assertTrue("Delayed worker did not complete",done)
            LocalStore(base).use { store ->
                val work=WorkCoordinator(store);assertEquals(2,store.notes().count { it.title.startsWith(title) })
                assertTrue(work.events(id).filter { it.state=="running" }.all { it.time>=start })
                assertEquals(listOf("completed","completed"),work.task(id)!!.steps.map { it.state })
            }
        } finally {
            LocalStore(base).use { store ->
                val work=WorkCoordinator(store);work.task(id)?.let { task ->
                    if(task.state !in setOf("completed","cancelled")) work.control(task,"cancel")
                    task.steps.forEach { it.resultId?.let(store::deleteNote) }
                }
                store.writableDatabase.delete("work_steps","work_id=?",arrayOf(id));store.writableDatabase.delete("durable_work_events","work_id=?",arrayOf(id));store.writableDatabase.delete("durable_work","id=?",arrayOf(id))
            }
        }
    }
}
