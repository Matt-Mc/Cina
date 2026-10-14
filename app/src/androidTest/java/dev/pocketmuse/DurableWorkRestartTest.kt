package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-process fixture: prepare, force-stop/relaunch the app, then verify. */
class DurableWorkRestartTest {
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=base.getSharedPreferences("durable-restart-test",0)
    @Test fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restart_fixture")=="true")
        LocalStore(base).use { store ->
            val work=WorkCoordinator(store)
            val pending=work.save(WorkAction("note","Restart pending fixture","Must not run"))
            val queued=work.savePlan(listOf(WorkAction("note","Restart first fixture","Already verified"),WorkAction("note","Restart approved fixture","Exactly one result")),null)
            work.approve(work.task(queued)!!)
            work.execute(queued)
            assertEquals(listOf("completed","pending"),work.task(queued)!!.steps.map { it.state })
            assertTrue(prefs.edit().putString("pending",pending).putString("queued",queued).commit())
        }
    }
    @Test fun verify() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restart_fixture")=="true")
        val pending=requireNotNull(prefs.getString("pending",null));val queued=requireNotNull(prefs.getString("queued",null))
        try {
            val deadline=System.currentTimeMillis()+30_000
            var completed=false
            while(System.currentTimeMillis()<deadline && !completed) {
                completed=LocalStore(base).use { WorkCoordinator(it).task(queued)?.state=="completed" }
                if(!completed) Thread.sleep(100)
            }
            assertTrue("Approved work did not recover after process restart",completed)
            LocalStore(base).use { store ->
                val work=WorkCoordinator(store)
                assertEquals("waiting_for_approval",work.task(pending)!!.state)
                assertEquals(1,store.notes().count { it.title=="Restart approved fixture" })
                assertEquals(1,store.notes().count { it.title=="Restart first fixture" })
                assertEquals(0,store.notes().count { it.title=="Restart pending fixture" })
                work.execute(queued);assertEquals(1,store.notes().count { it.title=="Restart approved fixture" })
            }
        } finally {
            LocalStore(base).use { store ->
                val work=WorkCoordinator(store)
                listOf(pending,queued).forEach { id ->
                    work.task(id)?.let { if(it.state !in setOf("completed","cancelled")) work.control(it,"cancel");it.steps.forEach { step -> step.resultId?.let(store::deleteNote) } }
                    store.writableDatabase.delete("work_steps","work_id=?",arrayOf(id))
                    store.writableDatabase.delete("durable_work_events","work_id=?",arrayOf(id));store.writableDatabase.delete("durable_work","id=?",arrayOf(id))
                }
            }
            prefs.edit().clear().commit()
        }
    }
}
