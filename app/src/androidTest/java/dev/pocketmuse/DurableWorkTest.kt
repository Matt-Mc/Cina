package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

internal fun removeWorkSchemaForMigrationTest(db: SQLiteDatabase) {
    listOf("work_source_delete","work_proposal_update").forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    listOf("work_steps","durable_work_events","durable_work").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
}

class DurableWorkTest {
    private fun fixture(block: (ContextWrapper,LocalStore,WorkCoordinator)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"work-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name: String)=file }
        try { LocalStore(context).use { block(context,it,WorkCoordinator(it)) } } finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun source(store: LocalStore): IntakeProposal {
        val inbox=InboxRepository(store);val text="Write a packing note."
        inbox.capture("source",CapturedSource("Packing","text/plain",text.toByteArray(),text,"Test"))
        inbox.saveInterpretation("source",1,InterpretationResult(listOf(IntakeSuggestion("action",text,"text",0,text.length,text)),"Complete source"),"Fixture")
        val p=inbox.review("source")!!.proposals.single();inbox.updateProposal("source",p.id,p.revision,text,"accepted")
        return inbox.review("source")!!.proposals.single()
    }
    @Test fun pendingApprovalSurvivesReopenAndCannotExecuteWithoutConsent()=fixture { context,store,work ->
        val id=work.save(WorkAction("note","Packing","Bring the blue bag."))
        work.execute(id);assertTrue(store.notes().isEmpty());store.close()
        LocalStore(context).use { reopened ->
            val next=WorkCoordinator(reopened);assertEquals("waiting_for_approval",next.task(id)!!.state)
            next.approve(next.task(id)!!);assertEquals("queued",next.task(id)!!.state)
        }
        LocalStore(context).use { reopened ->
            val next=WorkCoordinator(reopened);next.execute(id);next.execute(id)
            assertEquals(1,reopened.notes().size);assertEquals("completed",next.task(id)!!.state)
            assertEquals(listOf("waiting_for_approval","queued","running","verifying","completed"),next.events(id).map { it.state })
        }
    }
    @Test fun denialPauseAndResumeRevokeApproval()=fixture { _,store,work ->
        val cancelled=work.save(WorkAction("note","Denied","No write"));work.control(work.task(cancelled)!!,"cancel");work.execute(cancelled)
        assertTrue(runCatching { work.approve(work.task(cancelled)!!) }.isFailure)
        val paused=work.save(WorkAction("note","Paused","No write"));work.approve(work.task(paused)!!);work.control(work.task(paused)!!,"pause");work.execute(paused)
        work.control(work.task(paused)!!,"resume");work.execute(paused)
        assertTrue(store.notes().isEmpty());assertEquals("waiting_for_approval",work.task(paused)!!.state)
    }
    @Test fun editedAndStaleApprovalsCannotRun()=fixture { _,store,work ->
        val id=work.save(WorkAction("note","Before","First"));val stale=work.task(id)!!
        work.approve(stale);work.save(WorkAction("note","After","Second"),work.task(id))
        assertTrue(runCatching { work.approve(stale) }.isFailure);work.execute(id);assertTrue(store.notes().isEmpty())
        work.approve(work.task(id)!!);work.execute(id);assertEquals("Second",store.notes().single().body)
    }
    @Test fun expiredAndTamperedApprovalsRequireFreshReview()=fixture { _,store,work ->
        val now=System.currentTimeMillis()
        val expired=work.save(WorkAction("note","Expiry","Never silently run"));work.approve(work.task(expired)!!,now)
        work.execute(expired,now+WorkCoordinator.APPROVAL_MS);assertEquals("waiting_for_approval",work.task(expired)!!.state)
        val changed=work.save(WorkAction("note","Approved","Original"));work.approve(work.task(changed)!!,now)
        store.writableDatabase.execSQL("UPDATE durable_work SET body='Unapproved' WHERE id=?",arrayOf(changed))
        work.execute(changed,now+1);assertTrue(store.notes().isEmpty());assertEquals("waiting_for_approval",work.task(changed)!!.state)
    }
    @Test fun sourceChangesRevokeQueuedWorkAndRemovalCancelsIt()=fixture { _,store,work ->
        val p=source(store);val id=work.save(WorkAction("note","Packing","Blue bag"),from=p)
        assertEquals(id,work.save(WorkAction("note","Ignored duplicate","Other"),from=p));assertEquals(1,work.tasks().size)
        work.approve(work.task(id)!!)
        InboxRepository(store).updateProposal(p.intakeId,p.id,p.revision,"Write a different note.","pending")
        work.execute(id);assertTrue(store.notes().isEmpty());assertEquals("waiting_for_input",work.task(id)!!.state)
        assertTrue(runCatching { work.save(work.task(id)!!.action,work.task(id)) }.isFailure)
        InboxRepository(store).delete("source");assertEquals("cancelled",work.task(id)!!.state);assertNull(work.task(id)!!.sourceId)
        work.execute(id);assertTrue(store.notes().isEmpty())
    }
    @Test fun restoreRevokesSourceLinkedAuthorityAndNeverImportsJobs()=fixture { _,store,work ->
        val p=source(store);val backup=LibraryStorage(store).export()
        val id=work.save(WorkAction("note","Packing","Blue bag"),from=p);work.approve(work.task(id)!!)
        val preview=LibraryStorage(store).previewRestore(backup);LibraryStorage(store).restore(preview);preview.clear()
        assertEquals("cancelled",work.task(id)!!.state);work.execute(id);assertTrue(store.notes().isEmpty());assertEquals(1,work.tasks().size)
    }
    @Test fun failedCommitRollsBackSideEffectAndRetryCreatesExactlyOne()=fixture { _,store,work ->
        val id=work.save(WorkAction("note","Atomic","One note"));work.approve(work.task(id)!!)
        store.writableDatabase.execSQL("CREATE TRIGGER fail_completion BEFORE UPDATE ON durable_work WHEN new.state='completed' BEGIN SELECT RAISE(ABORT,'Injected crash boundary'); END")
        assertTrue(runCatching { work.execute(id) }.isFailure);assertTrue(store.notes().isEmpty());assertEquals("queued",work.task(id)!!.state)
        assertEquals(listOf("waiting_for_approval","queued"),work.events(id).map { it.state })
        store.writableDatabase.execSQL("DROP TRIGGER fail_completion")
        work.execute(id);work.execute(id);assertEquals(1,store.notes().size)
    }
    @Test fun competingExecutorsCannotDuplicateLocalWrites()=fixture { context,store,work ->
        val id=work.save(WorkAction("note","Concurrent","Once"));work.approve(work.task(id)!!)
        val pool=Executors.newFixedThreadPool(2)
        try { val futures=(1..2).map { pool.submit { LocalStore(context).use { WorkCoordinator(it).execute(id) };Unit } };futures.forEach { it.get() } }
        finally { pool.shutdownNow() }
        assertEquals(1,store.notes().size);assertEquals(1,work.events(id).count { it.state=="completed" })
    }
    @Test fun reminderReceiptProvesSaveButNeverRecreatesDeletedResult()=fixture { _,store,work ->
        val id=work.save(WorkAction("reminder","Bring bag",whenMillis=System.currentTimeMillis()+3600000))
        work.approve(work.task(id)!!);work.execute(id);val result=store.reminders().single()
        assertEquals(result.id,work.task(id)!!.resultId);store.deleteReminder(result.id);work.execute(id)
        assertTrue(store.reminders().isEmpty());assertEquals("completed",work.task(id)!!.state)
    }
    @Test fun schemaNineUpgradePreservesLibraryAndCreatesNoApprovals()=fixture { context,store,_ ->
        source(store);removeWorkSchemaForMigrationTest(store.writableDatabase);store.writableDatabase.version=9;store.close()
        LocalStore(context).use { upgraded ->
            assertEquals(11,upgraded.readableDatabase.version);assertTrue(WorkCoordinator(upgraded).tasks().isEmpty())
            assertEquals("accepted",InboxRepository(upgraded).review("source")!!.proposals.single().state)
        }
    }
}
