package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkPlanTest {
    private fun fixture(block: (ContextWrapper,LocalStore,WorkCoordinator)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"plan-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name: String)=file }
        try { LocalStore(context).use { block(context,it,WorkCoordinator(it)) } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private val actions=listOf(WorkAction("note","First","First body"),WorkAction("note","Second","Second body"))
    @Test fun orderedStepsResumeAfterReopenWithoutRepeatingCompletedWork()=fixture { context,store,work ->
        val id=work.savePlan(actions,null);work.approve(work.task(id)!!);work.execute(id)
        assertEquals(listOf("First"),store.notes().map { it.title });assertEquals("queued",work.task(id)!!.state)
        LocalStore(context).use { reopened ->
            val next=WorkCoordinator(reopened);next.execute(id);next.execute(id)
            assertEquals(2,reopened.notes().size);assertEquals("completed",next.task(id)!!.state)
            assertEquals(listOf("completed","completed"),next.task(id)!!.steps.map { it.state })
        }
    }
    @Test fun futurePlanCannotStartEarlyAndMissedWindowRequiresApproval()=fixture { _,store,work ->
        val now=System.currentTimeMillis();val start=now+86400000
        val id=work.savePlan(actions,start);work.approve(work.task(id)!!,now)
        work.execute(id,start-1);assertTrue(store.notes().isEmpty());assertEquals("queued",work.task(id)!!.state)
        work.execute(id,start+WorkCoordinator.APPROVAL_MS);assertTrue(store.notes().isEmpty());assertEquals("waiting_for_approval",work.task(id)!!.state)
        work.approve(work.task(id)!!,start+WorkCoordinator.APPROVAL_MS+1);work.execute(id,start+WorkCoordinator.APPROVAL_MS+2)
        assertEquals(1,store.notes().size)
    }
    @Test fun oneExpiredStepDoesNotRollBackOrRepeatEarlierVerifiedSteps()=fixture { _,store,work ->
        val now=System.currentTimeMillis();val id=work.savePlan(actions,null);work.approve(work.task(id)!!,now)
        work.execute(id,now+1);work.execute(id,now+WorkCoordinator.APPROVAL_MS)
        assertEquals(1,store.notes().size);assertEquals("waiting_for_approval",work.task(id)!!.state)
        work.approve(work.task(id)!!,now+WorkCoordinator.APPROVAL_MS+1);work.execute(id,now+WorkCoordinator.APPROVAL_MS+2)
        assertEquals(2,store.notes().size)
    }
    @Test fun partialPlanCanEditOnlyRemainingStepsAndMustReapprove()=fixture { _,store,work ->
        val id=work.savePlan(actions,null);work.approve(work.task(id)!!);work.execute(id)
        val partial=work.task(id)!!
        assertTrue(runCatching { work.savePlan(actions.reversed(),null,partial) }.isFailure)
        assertTrue(runCatching { work.savePlan(listOf(actions.last()),null,partial) }.isFailure)
        work.savePlan(listOf(actions.first(),actions.last().copy(body="Revised")),null,partial)
        work.execute(id);assertEquals(1,store.notes().size)
        work.approve(work.task(id)!!);work.execute(id)
        assertEquals("Revised",store.notes().first { it.title=="Second" }.body)
    }
    @Test fun pauseAndCancellationStopRemainingSteps()=fixture { _,store,work ->
        val id=work.savePlan(actions,null);work.approve(work.task(id)!!);work.execute(id)
        work.control(work.task(id)!!,"pause");work.execute(id);assertEquals(1,store.notes().size)
        work.control(work.task(id)!!,"resume");work.execute(id);assertEquals(1,store.notes().size)
        work.control(work.task(id)!!,"cancel");work.execute(id);assertEquals(1,store.notes().size)
        assertEquals("completed",work.task(id)!!.steps.first().state)
    }
    @Test fun failureOfLaterStepKeepsOnlyEarlierReceiptAndRetryIsSafe()=fixture { _,store,work ->
        val id=work.savePlan(actions,null);work.approve(work.task(id)!!);work.execute(id)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON notes WHEN new.title='Second' BEGIN SELECT RAISE(ABORT,'Injected failure'); END")
        assertTrue(runCatching { work.execute(id) }.isFailure);assertEquals(1,store.notes().size)
        assertEquals(listOf("completed","pending"),work.task(id)!!.steps.map { it.state })
        store.writableDatabase.execSQL("DROP TRIGGER fail_second");work.execute(id);assertEquals(2,store.notes().size)
    }
    @Test fun stepAndScheduleTamperingCannotReuseGrant()=fixture { _,store,work ->
        val id=work.savePlan(actions,null);work.approve(work.task(id)!!)
        store.writableDatabase.execSQL("UPDATE work_steps SET body='Changed' WHERE work_id=? AND ordinal=1",arrayOf(id))
        work.execute(id);assertTrue(store.notes().isEmpty());assertEquals("waiting_for_approval",work.task(id)!!.state)
        work.approve(work.task(id)!!)
        store.writableDatabase.execSQL("UPDATE durable_work SET run_at=? WHERE id=?",arrayOf<Any>(System.currentTimeMillis()+10000,id))
        work.execute(id);assertTrue(store.notes().isEmpty())
    }
    @Test fun planLimitsAndReminderTimingAreValidated()=fixture { _,_,work ->
        assertTrue(runCatching { work.savePlan(emptyList(),null) }.isFailure)
        assertTrue(runCatching { work.savePlan(List(9) { actions.first() },null) }.isFailure)
        val start=System.currentTimeMillis()+3600000
        assertTrue(runCatching { work.savePlan(listOf(WorkAction("reminder","Too early",whenMillis=start-1)),start) }.isFailure)
    }
    @Test fun scheduledPromptsStageIdempotentReviewTasksInsteadOfExecutingWrites()=fixture { context,store,work -> runBlocking {
        val tools=AssistantTools(context,store,scheduledModelPath="fixture")
        val request=ToolRequest("create_note",JSONObject().put("title","Scheduled draft").put("body","Review me"))
        val first=JSONObject(tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:1:100"))
        val retry=JSONObject(tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:1:100"))
        assertEquals("pending_approval",first.getString("status"));assertEquals(first.getString("work_id"),retry.getString("work_id"))
        assertEquals(1,work.tasks().size);assertTrue(store.notes().isEmpty())
        work.approve(work.tasks().single());work.execute(work.tasks().single().id)
        tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:1:100");assertEquals(1,store.notes().size)
        tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:1:200");assertEquals(2,work.tasks().size);assertEquals(1,store.notes().size)
        val unsupported=ToolRequest("create_scheduled_task",JSONObject().put("title","Unattended chain").put("prompt","Write").put("when_text","in 10 minutes").put("repeat","daily"))
        assertTrue(runCatching { tools.execute(Chat(0,"Schedule",false,false),unsupported,"scheduled:1:200") }.isFailure)
    } }
    @Test fun schemaTenMigrationKeepsReceiptsAndRevokesOldQueuedGrants()=fixture { context,store,work ->
        val done=work.save(actions.first());work.approve(work.task(done)!!);work.execute(done)
        val queued=work.save(actions.last());work.approve(work.task(queued)!!)
        val db=store.writableDatabase
        db.execSQL("CREATE TABLE old_work AS SELECT id,revision,state,kind,title,body,when_ms,source_id,proposal_id,proposal_revision,approved_hash,approval_until,result_id,message FROM durable_work")
        removeWorkSchemaForMigrationTest(db);WorkCoordinator.createTables(db);db.execSQL("INSERT INTO durable_work SELECT * FROM old_work");db.execSQL("DROP TABLE old_work");db.version=10;store.close()
        LocalStore(context).use { upgraded ->
            val next=WorkCoordinator(upgraded);assertEquals(12,upgraded.readableDatabase.version)
            assertEquals("completed",next.task(done)!!.steps.single().state);next.execute(done)
            assertEquals("waiting_for_approval",next.task(queued)!!.state);next.execute(queued);assertEquals(1,upgraded.notes().size)
        }
    }
    @Test fun scheduledReminderRetryKeepsItsOriginalTimeAndOldReceiptsDoNotReplay()=fixture { context,store,work -> runBlocking {
        val tools=AssistantTools(context,store,scheduledModelPath="fixture")
        val request=ToolRequest("create_reminder",JSONObject().put("title","Scheduled reminder").put("when_text","in 10 minutes"))
        val first=JSONObject(tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:2:100"))
        val id=first.getString("work_id");val time=work.task(id)!!.action.whenMillis
        val retry=JSONObject(tools.execute(Chat(0,"Schedule",false,false),request,"scheduled:2:100"))
        assertEquals(id,retry.getString("work_id"));assertEquals(time,work.task(id)!!.action.whenMillis);assertTrue(store.reminders().isEmpty())
        val scope="scheduled:2:legacy";val key=tools.callKey(request)
        store.beginTool(scope,key);val old=JSONObject().put("status","success").put("message","Previously saved").toString();store.finishTool(scope,key,old)
        assertEquals(old,tools.execute(Chat(0,"Schedule",false,false),request,scope));assertEquals(1,work.tasks().size)
    } }
}
