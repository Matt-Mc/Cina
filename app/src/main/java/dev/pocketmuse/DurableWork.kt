package dev.pocketmuse

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

internal data class WorkAction(val kind: String, val title: String, val body: String = "", val whenMillis: Long? = null) {
    fun validate(now: Long) {
        require(kind in setOf("note","reminder")) { "Only local notes and reminders are supported." }
        require(title.isNotBlank() && title.length<=160) { "Add a title of up to 160 characters." }
        require(if(kind=="note") body.isNotBlank() && body.length<=20_000 && whenMillis==null else body.isEmpty() && whenMillis!=null && whenMillis>now) {
            "Add note text, or choose a future reminder time." }
    }
    fun fingerprint(): String {
        // Length prefixes prevent ambiguity even when user text includes separators.
        val fields=listOf(kind,title,body,whenMillis?.toString().orEmpty())
        return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
internal data class DurableTask(val id: String,val revision: Int,val state: String,val action: WorkAction,
    val sourceId: String?,val proposalId: String?,val proposalRevision: Int?,val approvedHash: String?,val approvalUntil: Long?,
    val resultId: Long?,val message: String,val steps: List<WorkStep> = emptyList(),val runAt: Long?=null,val approvalFrom: Long?=null)
internal data class WorkStep(val ordinal: Int,val action: WorkAction,val state: String,val resultId: Long?)
internal data class WorkEvent(val state: String,val detail: String,val time: Long)

/** Each verified local step and its receipt commit together. Plans never replay completed steps. */
internal class WorkCoordinator(private val store: LocalStore) {
    companion object {
        const val APPROVAL_MS=15*60*1000L
        const val MAX_STEPS=8
        fun createPlanTables(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE durable_work ADD COLUMN run_at INTEGER")
            db.execSQL("ALTER TABLE durable_work ADD COLUMN approval_from INTEGER")
            db.execSQL("ALTER TABLE durable_work ADD COLUMN origin_key TEXT")
            db.execSQL("CREATE UNIQUE INDEX durable_work_origin ON durable_work(origin_key)")
            db.execSQL("CREATE TABLE work_steps(work_id TEXT NOT NULL,ordinal INTEGER NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,when_ms INTEGER,state TEXT NOT NULL,result_id INTEGER,PRIMARY KEY(work_id,ordinal))")
            db.execSQL("INSERT INTO work_steps SELECT id,0,kind,title,body,when_ms,CASE WHEN state='completed' THEN 'completed' ELSE 'pending' END,result_id FROM durable_work")
            // Version 10 approvals bind a different payload; never reinterpret them as plan grants.
            db.execSQL("UPDATE durable_work SET state='waiting_for_approval',revision=revision+1,approved_hash=NULL,approval_until=NULL,message='Review this task again after the work-system update.' WHERE state='queued'")
        }
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE durable_work(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,state TEXT NOT NULL,
                kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,when_ms INTEGER,
                source_id TEXT,proposal_id TEXT UNIQUE,proposal_revision INTEGER,
                approved_hash TEXT,approval_until INTEGER,result_id INTEGER,message TEXT NOT NULL DEFAULT '')""")
            db.execSQL("CREATE TABLE durable_work_events(id INTEGER PRIMARY KEY,work_id TEXT NOT NULL,state TEXT NOT NULL,detail TEXT NOT NULL,time_ms INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX durable_work_events_owner ON durable_work_events(work_id)")
            db.execSQL("""CREATE TRIGGER work_source_delete BEFORE DELETE ON sources BEGIN
                INSERT INTO durable_work_events(work_id,state,detail,time_ms)
                    SELECT id,CASE WHEN state='completed' THEN state ELSE 'cancelled' END,'Source removed; pending authority revoked',CAST(strftime('%s','now') AS INTEGER)*1000 FROM durable_work WHERE source_id=old.id;
                UPDATE durable_work SET state=CASE WHEN state='completed' THEN state ELSE 'cancelled' END,
                    revision=revision+1,approved_hash=NULL,approval_until=NULL,source_id=NULL,proposal_id=NULL,proposal_revision=NULL,
                    message='Source removed. Existing results remain; pending work was cancelled.' WHERE source_id=old.id;
            END""")
            db.execSQL("""CREATE TRIGGER work_proposal_update AFTER UPDATE ON intake_proposals BEGIN
                INSERT INTO durable_work_events(work_id,state,detail,time_ms)
                    SELECT id,'waiting_for_input','Source suggestion changed; review task again',CAST(strftime('%s','now') AS INTEGER)*1000 FROM durable_work WHERE proposal_id=old.id AND state NOT IN ('completed','cancelled');
                UPDATE durable_work SET state='waiting_for_input',revision=revision+1,approved_hash=NULL,approval_until=NULL,
                    message='Source suggestion changed. Keep the latest suggestion, then edit and save this task.' WHERE proposal_id=old.id AND state NOT IN ('completed','cancelled');
            END""")
        }
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db=store.writableDatabase;db.beginTransaction()
        try { val result=block(db);db.setTransactionSuccessful();return result } finally { db.endTransaction() }
    }
    fun steps(id: String): List<WorkStep> = store.readableDatabase.rawQuery("SELECT ordinal,kind,title,body,when_ms,state,result_id FROM work_steps WHERE work_id=? ORDER BY ordinal",arrayOf(id)).use { c -> buildList {
        while(c.moveToNext()) add(WorkStep(c.getInt(0),WorkAction(c.getString(1),c.getString(2),c.getString(3),if(c.isNull(4)) null else c.getLong(4)),c.getString(5),if(c.isNull(6)) null else c.getLong(6)))
    } }
    fun tasks(): List<DurableTask> = store.readableDatabase.rawQuery("SELECT id,revision,state,kind,title,body,when_ms,source_id,proposal_id,proposal_revision,approved_hash,approval_until,result_id,message,run_at,approval_from FROM durable_work ORDER BY rowid DESC",null).use { c -> buildList {
        while(c.moveToNext()) add(DurableTask(c.getString(0),c.getInt(1),c.getString(2),WorkAction(c.getString(3),c.getString(4),c.getString(5),if(c.isNull(6)) null else c.getLong(6)),
            if(c.isNull(7)) null else c.getString(7),if(c.isNull(8)) null else c.getString(8),if(c.isNull(9)) null else c.getInt(9),
            if(c.isNull(10)) null else c.getString(10),if(c.isNull(11)) null else c.getLong(11),if(c.isNull(12)) null else c.getLong(12),c.getString(13),steps(c.getString(0)),if(c.isNull(14)) null else c.getLong(14),if(c.isNull(15)) null else c.getLong(15)))
    } }
    fun task(id: String)=tasks().firstOrNull { it.id==id }
    fun stageScheduled(origin: String,prepare: ()->WorkAction): DurableTask = transaction { db ->
        val existing=db.rawQuery("SELECT id FROM durable_work WHERE origin_key=?",arrayOf(origin)).use { if(it.moveToFirst()) it.getString(0) else null }
        if(existing!=null) return@transaction requireNotNull(task(existing))
        val id=save(prepare())
        db.execSQL("UPDATE durable_work SET origin_key=?,message=? WHERE id=?",arrayOf(origin,"Suggested by a scheduled prompt. Review exact details before approving.",id))
        requireNotNull(task(id))
    }
    fun events(id: String): List<WorkEvent> = store.readableDatabase.rawQuery("SELECT state,detail,time_ms FROM durable_work_events WHERE work_id=? ORDER BY id",arrayOf(id)).use { c -> buildList { while(c.moveToNext()) add(WorkEvent(c.getString(0),c.getString(1),c.getLong(2))) } }
    private fun event(db: SQLiteDatabase,id: String,state: String,detail: String,now: Long) {
        db.insertOrThrow("durable_work_events",null,ContentValues().apply { put("work_id",id);put("state",state);put("detail",detail);put("time_ms",now) })
    }
    private fun proposal(id: String,source: String): IntakeProposal {
        val p=InboxRepository(store).review(source)?.proposals?.firstOrNull { it.id==id }
        require(p!=null && p.state=="accepted" && p.suggestion.kind=="action") { "Keep the latest action suggestion in the Inbox before saving this task." }
        return p
    }
    fun save(action: WorkAction,existing: DurableTask?=null,from: IntakeProposal?=null,now: Long=System.currentTimeMillis()): String = savePlan(listOf(action),null,existing,from,now)
    fun savePlan(actions: List<WorkAction>,runAt: Long?,existing: DurableTask?=null,from: IntakeProposal?=null,now: Long=System.currentTimeMillis()): String = transaction { db ->
        require(actions.size in 1..MAX_STEPS) { "Use one to eight steps." }
        require(runAt==null || runAt>now && runAt<=now+365L*86400000) { "Choose a start time within the next year, or run after approval." }
        val completed=existing?.let { task(it.id)?.steps?.takeWhile { step -> step.state=="completed" } }.orEmpty()
        require(actions.size>completed.size && completed.indices.all { completed[it].action==actions[it] }) { "Completed steps cannot be edited or removed. Change only the remaining steps." }
        actions.drop(completed.size).forEach { it.validate(maxOf(now,runAt ?: now)) }
        val action=actions.first()
        if(existing!=null) require(task(existing.id)?.let { it.revision==existing.revision && it.state !in setOf("completed","cancelled") }==true) { "Task changed. Reopen it before editing." }
        require(existing==null || from==null)
        val source=existing?.sourceId ?: from?.intakeId
        val proposalId=existing?.proposalId ?: from?.id
        val fresh=if(proposalId!=null && source!=null) proposal(proposalId,source) else null
        if(from!=null) {
            require(fresh?.revision==from.revision) { "Suggestion changed. Open it again." }
            tasks().firstOrNull { it.proposalId==from.id }?.let { return@transaction it.id }
        }
        val id=existing?.id ?: UUID.randomUUID().toString()
        val values=ContentValues().apply {
            put("revision",(existing?.revision ?: 0)+1);put("state","waiting_for_approval");put("kind",action.kind);put("title",action.title);put("body",action.body);put("when_ms",action.whenMillis)
            put("source_id",source);put("proposal_id",proposalId);put("proposal_revision",fresh?.revision);putNull("approved_hash");putNull("approval_until");put("message","Review the exact details before approving.")
            put("run_at",runAt);putNull("approval_from")
        }
        if(existing==null) { values.put("id",id);db.insertOrThrow("durable_work",null,values) } else db.update("durable_work",values,"id=?",arrayOf(id))
        db.delete("work_steps","work_id=? AND ordinal>=?",arrayOf(id,completed.size.toString()))
        actions.forEachIndexed { ordinal,step -> if(ordinal>=completed.size) db.insertOrThrow("work_steps",null,ContentValues().apply {
            put("work_id",id);put("ordinal",ordinal);put("kind",step.kind);put("title",step.title);put("body",step.body);put("when_ms",step.whenMillis);put("state","pending")
        }) }
        event(db,id,"waiting_for_approval",if(existing==null) "Task saved; no action authorized" else "Task edited; previous approval revoked",now)
        id
    }
    private fun fresh(task: DurableTask): DurableTask = this.task(task.id)?.also {
        require(it.revision==task.revision) { "Task changed. Reopen it before continuing." }
    } ?: error("Task no longer exists.")
    private fun sourceValid(task: DurableTask): Boolean = task.proposalId==null || runCatching {
        proposal(task.proposalId,requireNotNull(task.sourceId)).revision==task.proposalRevision
    }.getOrDefault(false)
    private fun planHash(task: DurableTask): String {
        val fields=listOf(task.runAt?.toString().orEmpty())+task.steps.map { it.action.fingerprint() }
        return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("|").toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun approve(task: DurableTask,now: Long=System.currentTimeMillis()) = transaction { db ->
        val current=fresh(task)
        require(current.state=="waiting_for_approval") { "This task is not waiting for approval." }
        require(current.steps.size in 1..MAX_STEPS && current.steps.any { it.state=="pending" })
        val start=maxOf(now,current.runAt ?: now)
        current.steps.filter { it.state=="pending" }.forEach { it.action.validate(start) }
        require(sourceValid(current)) { "Source suggestion changed. Edit and review the task again." }
        db.update("durable_work",ContentValues().apply { put("state","queued");put("revision",current.revision+1);put("approved_hash",planHash(current));put("approval_from",start);put("approval_until",start+APPROVAL_MS);put("message","Approved for this plan and start time; waiting for Android.") },"id=?",arrayOf(current.id))
        event(db,current.id,"queued","Remaining steps approved for one run from $start until ${start+APPROVAL_MS}",now)
    }
    fun control(task: DurableTask,command: String,now: Long=System.currentTimeMillis()) = transaction { db ->
        val current=fresh(task);require(current.state !in setOf("completed","cancelled")) { "This task has already finished." }
        val state=when(command) { "cancel" -> "cancelled";"pause" -> "paused";"resume" -> { require(current.state=="paused");"waiting_for_approval" };else -> error("Invalid task command.") }
        db.update("durable_work",ContentValues().apply { put("state",state);put("revision",current.revision+1);putNull("approved_hash");putNull("approval_until");put("message",if(state=="waiting_for_approval") "Review and approve again to continue." else "No further action will run.") },"id=?",arrayOf(current.id))
        event(db,current.id,state,"User selected $command; approval revoked",now)
    }
    /** Called only by the local worker. No model, chat grant, imported text or UI flag can bypass this check. */
    fun execute(id: String,atMillis: Long?=null): DurableTask? = transaction { db ->
        // Read wall time after acquiring the write transaction: a queued writer may have waited for a lock.
        val now=atMillis ?: System.currentTimeMillis()
        val task=task(id) ?: return@transaction null
        if(task.state!="queued") return@transaction task
        val pending=task.steps.firstOrNull { it.state=="pending" }
        val grantValid=task.approvalFrom!=null && task.approvalUntil==task.approvalFrom+APPROVAL_MS && task.approvedHash==planHash(task) && sourceValid(task) && task.steps.size in 1..MAX_STEPS && task.action==task.steps.first().action
        if(grantValid && now<task.approvalFrom) return@transaction task
        val valid=grantValid && task.approvalUntil>now && pending!=null && runCatching { pending.action.validate(now) }.isSuccess
        if(!valid) {
            db.update("durable_work",ContentValues().apply { put("state","waiting_for_approval");put("revision",task.revision+1);putNull("approved_hash");putNull("approval_until");put("message","Approval expired or details changed. Review before approving again.") },"id=?",arrayOf(id))
            event(db,id,"waiting_for_approval","Execution refused: fresh exact-action approval required",now)
            return@transaction task(id)
        }
        val step=requireNotNull(pending);val action=step.action
        event(db,id,"running","Step ${step.ordinal+1}: creating the approved local ${action.kind}",now)
        val result=when(action.kind) {
            "note" -> store.addNote(action.title,action.body)
            "reminder" -> store.addReminder(action.title,requireNotNull(action.whenMillis))
            else -> error("Unsupported action.")
        }
        event(db,id,"verifying","Reading the saved item back",now)
        val verified=if(action.kind=="note") store.notes().any { it.id==result && it.title==action.title && it.body==action.body }
            else store.reminders().any { it.id==result && it.title==action.title && it.whenMillis==action.whenMillis && !it.done }
        check(verified) { "The saved result could not be verified." }
        db.update("work_steps",ContentValues().apply { put("state","completed");put("result_id",result) },"work_id=? AND ordinal=?",arrayOf(id,step.ordinal.toString()))
        val finished=task.steps.count { it.state=="pending" }==1
        db.update("durable_work",ContentValues().apply {
            put("state",if(finished) "completed" else "queued");put("revision",task.revision+1);put("result_id",result)
            if(finished) { putNull("approved_hash");putNull("approval_until");putNull("approval_from") }
            put("message",if(finished) "All steps saved and verified. Reminder notifications depend on Android permissions and timing." else "Step ${step.ordinal+1} saved and verified; remaining steps will continue.")
        },"id=?",arrayOf(id))
        event(db,id,if(finished) "completed" else "step_completed","Verified ${action.kind} #$result; step ${step.ordinal+1} complete",now)
        task(id)
    }
}
