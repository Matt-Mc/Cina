package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkToolsTest {
    private fun fixture(block: (LocalStore,WorkCoordinator)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"work-tools-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name:String)=file }
        try { LocalStore(context).use { block(it,WorkCoordinator(it)) } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun call(store:LocalStore,name:String,json:String,key:String=json):JSONObject {
        val args=JSONObject(json);ToolCatalog.get(name)!!.validate(args)
        return JSONObject(WorkTools.execute(store,ToolRequest(name,args),"test",name+key))
    }
    @Test fun draftsAppendAndScheduleWithoutExecutionAndRetryIsAtomic()=fixture { store,work ->
        val json="""{"kind":"note","title":"Trip","body":"Pack a coat"}"""
        val first=call(store,"draft_work_step",json)
        assertEquals(first.toString(),call(store,"draft_work_step",json).toString())
        val id=first.getJSONObject("data").getString("work_id")
        call(store,"append_work_step","""{"work_id":"$id","revision":1,"kind":"note","title":"Tickets","body":"Print tickets"}""")
        call(store,"schedule_work","""{"work_id":"$id","revision":2,"when_text":"in 1 hour"}""")
        assertEquals(1,work.tasks().size);assertEquals(2,work.task(id)!!.steps.size)
        assertEquals("waiting_for_approval",work.task(id)!!.state)
        assertNull(work.task(id)!!.approvedHash);work.execute(id);assertTrue(store.notes().isEmpty())
    }
    @Test fun staleRevisionAndInventedAuthorityFailClosed()=fixture { store,work ->
        val id=work.save(WorkAction("note","A","B"));work.approve(work.task(id)!!)
        assertTrue(runCatching { call(store,"manage_work","""{"work_id":"$id","revision":1,"command":"cancel"}""") }.isFailure)
        assertTrue(runCatching { call(store,"manage_work","""{"work_id":"$id","revision":2,"command":"approve"}""") }.isFailure)
        assertTrue(runCatching { call(store,"draft_work_step","""{"kind":"note","title":"A","body":"B","approved":true}""") }.isFailure)
        assertEquals("queued",work.task(id)!!.state);assertTrue(store.notes().isEmpty())
    }
    @Test fun aTurnCannotAccidentallyCreateAnotherPlanAndAppendRemainsAvailable()=fixture { store,work ->
        call(store,"draft_work_step","""{"kind":"note","title":"A","body":"First"}""")
        assertTrue(runCatching { call(store,"draft_work_step","""{"kind":"note","title":"B","body":"Second"}""") }.isFailure)
        val task=work.tasks().single()
        call(store,"append_work_step","""{"work_id":"${task.id}","revision":1,"kind":"note","title":"B","body":"Second"}""")
        assertEquals(1,work.tasks().size);assertEquals(2,work.tasks().single().steps.size);assertTrue(store.notes().isEmpty())
    }
    @Test fun missingReminderTimeDoesNotCreateDraftOrReceipt()=fixture { store,work ->
        assertTrue(runCatching { call(store,"draft_work_step","""{"kind":"reminder","title":"Call Sam"}""") }.isFailure)
        assertTrue(work.tasks().isEmpty());assertTrue(store.toolHistory("test").isEmpty())
    }
    @Test fun pauseResumeAndReadPreserveStepsButRevokeAuthority()=fixture { store,work ->
        val id=work.savePlan(listOf(WorkAction("note","A","B"),WorkAction("note","C","D")),null)
        work.approve(work.task(id)!!);work.execute(id)
        call(store,"manage_work","""{"work_id":"$id","revision":${work.task(id)!!.revision},"command":"pause"}""")
        call(store,"manage_work","""{"work_id":"$id","revision":${work.task(id)!!.revision},"command":"resume"}""")
        val data=call(store,"read_work","""{"work_id":"$id"}""").getJSONObject("data")
        assertEquals(1,data.getInt("completed_steps"));assertEquals("waiting_for_approval",data.getString("state"))
        work.execute(id);assertEquals(1,store.notes().size)
    }
    @Test fun discoveryPaginationAndScheduledPermissions()=fixture { store,work ->
        repeat(10) { work.save(WorkAction("note","Plan $it","Body")) }
        val page=call(store,"list_work","""{"query":"Plan"}""").getJSONObject("data")
        assertEquals(8,page.getJSONArray("items").length());assertEquals(8,page.getInt("next_offset"))
        assertTrue(ToolCatalog.select("pause my plan").any { it.name=="manage_work" })
        assertFalse(ToolCatalog.select("",scheduled=true).any { it.name in setOf("manage_work","draft_work_step") })
    }
    @Test fun followUpsRetryFailedDeliveryAndSuppressRepeatedSweeps()=fixture { _,work ->
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=base.getSharedPreferences("follow-up-test-${System.nanoTime()}",0)
        try {
            val id=work.save(WorkAction("note","Private title","Private body"));var posts=0
            WorkFollowUps.deliver(work.tasks(),prefs) { _,_ -> false }
            assertTrue(prefs.all.isEmpty())
            repeat(3) { WorkFollowUps.deliver(work.tasks(),prefs) { _,_ -> posts++;true } }
            assertEquals(1,posts)
            work.approve(work.task(id)!!)
            WorkFollowUps.deliver(work.tasks(),prefs) { _,_ -> posts++;true };assertEquals(1,posts)
            work.execute(id)
            repeat(3) { WorkFollowUps.deliver(work.tasks(),prefs) { _,label -> assertEquals("Local work completed",label);posts++;true } }
            assertEquals(2,posts)
        } finally { prefs.edit().clear().commit() }
    }
}
