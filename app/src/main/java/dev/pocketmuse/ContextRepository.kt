package dev.pocketmuse

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class ContextProject(val id: String, val name: String, val revision: Int)
data class KnowledgeEntry(val id: String, val sourceId: String, val proposalId: String, val text: String,
    val revision: Int, val status: String, val expiresAt: Long?, val quote: String, val field: String, val start: Int, val end: Int)
data class KnowledgeRevision(val revision: Int, val text: String, val status: String, val reason: String, val changedAt: Long)
data class ContextHit(val sourceId: String, val recordId: String, val kind: String, val title: String,
    val body: String, val start: Int, val end: Int)

class ContextRepository(private val store: LocalStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE context_projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE project_sources(project_id TEXT NOT NULL,source_id TEXT NOT NULL,PRIMARY KEY(project_id,source_id))")
            db.execSQL("CREATE TABLE project_history(project_id TEXT NOT NULL,revision INTEGER NOT NULL,name TEXT NOT NULL,PRIMARY KEY(project_id,revision))")
            db.execSQL("CREATE TABLE knowledge(id TEXT PRIMARY KEY,source_id TEXT NOT NULL,proposal_id TEXT NOT NULL UNIQUE,proposal_revision INTEGER NOT NULL,text TEXT NOT NULL,revision INTEGER NOT NULL,status TEXT NOT NULL,expires_ms INTEGER,evidence TEXT NOT NULL)")
            db.execSQL("CREATE TABLE knowledge_history(knowledge_id TEXT NOT NULL,revision INTEGER NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,expires_ms INTEGER,reason TEXT NOT NULL,changed_ms INTEGER NOT NULL,PRIMARY KEY(knowledge_id,revision))")
            db.execSQL("CREATE VIRTUAL TABLE context_search USING fts4(body,title,source_id,kind,record_id,start_char,end_char,notindexed=source_id,notindexed=kind,notindexed=record_id,notindexed=start_char,notindexed=end_char,tokenize=unicode61)")
            db.execSQL("INSERT INTO context_search SELECT p.body,s.title,p.source_id,'passage',p.source_id||':'||p.ordinal,p.start_char,p.end_char FROM source_passages p JOIN sources s ON s.id=p.source_id")
            db.execSQL("INSERT INTO context_search SELECT accompanying_text,title,id,'caption',id,0,length(accompanying_text) FROM sources WHERE accompanying_text<>''")
            db.execSQL("INSERT INTO context_search SELECT '',title,id,'title',id,0,0 FROM sources")
            db.execSQL("""CREATE TRIGGER context_source_insert AFTER INSERT ON sources BEGIN
                INSERT INTO context_search VALUES('',new.title,new.id,'title',new.id,0,0);
                INSERT INTO context_search SELECT new.accompanying_text,new.title,new.id,'caption',new.id,0,length(new.accompanying_text) WHERE new.accompanying_text<>'';
            END""")
            db.execSQL("""CREATE TRIGGER context_passage_insert AFTER INSERT ON source_passages BEGIN
                INSERT INTO context_search SELECT new.body,title,new.source_id,'passage',new.source_id||':'||new.ordinal,new.start_char,new.end_char FROM sources WHERE id=new.source_id;
            END""")
            db.execSQL("""CREATE TRIGGER context_source_delete AFTER DELETE ON sources BEGIN
                DELETE FROM context_search WHERE source_id=old.id;
                DELETE FROM knowledge_history WHERE knowledge_id IN (SELECT id FROM knowledge WHERE source_id=old.id);
                DELETE FROM knowledge WHERE source_id=old.id;
                DELETE FROM project_sources WHERE source_id=old.id;
            END""")
            db.execSQL("""CREATE TRIGGER context_knowledge_insert AFTER INSERT ON knowledge WHEN new.status='active' BEGIN
                INSERT INTO context_search SELECT new.text,title,new.source_id,'knowledge',new.id,0,length(new.text) FROM sources WHERE id=new.source_id;
            END""")
            db.execSQL("""CREATE TRIGGER context_knowledge_update AFTER UPDATE ON knowledge BEGIN
                DELETE FROM context_search WHERE kind='knowledge' AND record_id=old.id;
                INSERT INTO context_search SELECT new.text,title,new.source_id,'knowledge',new.id,0,length(new.text) FROM sources WHERE id=new.source_id AND new.status='active';
            END""")
            db.execSQL("""CREATE TRIGGER context_knowledge_delete AFTER DELETE ON knowledge BEGIN
                DELETE FROM context_search WHERE kind='knowledge' AND record_id=old.id;
                DELETE FROM knowledge_history WHERE knowledge_id=old.id;
            END""")
        }
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = store.writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    fun projects(): List<ContextProject> = store.readableDatabase.rawQuery("SELECT id,name,revision FROM context_projects ORDER BY name COLLATE NOCASE,id", null).use { c -> buildList {
        while(c.moveToNext()) add(ContextProject(c.getString(0),c.getString(1),c.getInt(2)))
    } }
    fun saveProject(name: String, existing: ContextProject? = null): String = transaction { db ->
        val clean = name.trim(); require(clean.length in 1..80) { "Use a project name of 1–80 characters." }
        require(projects().none { it.id != existing?.id && it.name.equals(clean, true) }) { "A project with that name already exists." }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val revision = (existing?.revision ?: 0) + 1
        if(existing == null) db.insertOrThrow("context_projects", null, ContentValues().apply { put("id",id);put("name",clean) })
        else require(db.update("context_projects",ContentValues().apply { put("name",clean);put("revision",revision) },"id=? AND revision=?",arrayOf(id,existing.revision.toString())) == 1) { "Project changed. Reopen it and try again." }
        db.insertOrThrow("project_history",null,ContentValues().apply { put("project_id",id);put("revision",revision);put("name",clean) })
        id
    }
    fun deleteProject(id: String) = transaction { db ->
        db.delete("project_sources","project_id=?",arrayOf(id)); db.delete("project_history","project_id=?",arrayOf(id)); db.delete("context_projects","id=?",arrayOf(id)); Unit
    }
    fun sourceProjects(id: String): Set<String> = store.readableDatabase.rawQuery("SELECT project_id FROM project_sources WHERE source_id=?", arrayOf(id)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    fun linkSource(sourceId: String, projectId: String, linked: Boolean) = transaction { db ->
        require(InboxRepository(store).contains(sourceId)) { "Source no longer exists." }
        require(projects().any { it.id == projectId }) { "Project no longer exists." }
        if(linked) db.insertWithOnConflict("project_sources",null,ContentValues().apply { put("project_id",projectId);put("source_id",sourceId) },SQLiteDatabase.CONFLICT_IGNORE)
        else db.delete("project_sources","project_id=? AND source_id=?",arrayOf(projectId,sourceId))
        Unit
    }
    fun knowledge(sourceId: String? = null): List<KnowledgeEntry> = store.readableDatabase.rawQuery(
        "SELECT id,source_id,proposal_id,text,revision,status,expires_ms,evidence FROM knowledge" + if(sourceId != null) " WHERE source_id=? ORDER BY rowid DESC" else " ORDER BY rowid DESC",
        sourceId?.let { arrayOf(it) }).use { c -> buildList { while(c.moveToNext()) {
            val e = org.json.JSONObject(c.getString(7))
            add(KnowledgeEntry(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getString(5),if(c.isNull(6)) null else c.getLong(6),e.getString("quote"),e.getString("field"),e.getInt("start"),e.getInt("end")))
        } } }
    private fun record(db: SQLiteDatabase, id: String, reason: String) {
        db.execSQL("INSERT INTO knowledge_history SELECT id,revision,text,status,expires_ms,?,? FROM knowledge WHERE id=?", arrayOf<Any>(reason,System.currentTimeMillis(),id))
    }
    fun promote(proposal: IntakeProposal, expiresAt: Long? = null): String = transaction { db ->
        require(expiresAt == null || expiresAt > System.currentTimeMillis()) { "Choose a future validity date." }
        val fresh = InboxRepository(store).review(proposal.intakeId)?.proposals?.singleOrNull { it.id == proposal.id } ?: error("Suggestion no longer exists.")
        require(fresh.revision == proposal.revision && fresh.state == "accepted" && fresh.suggestion.kind == "fact") { "Keep the latest fact suggestion before saving it as knowledge." }
        require(!MemorySafety.isCredential(fresh.suggestion.text + " " + fresh.suggestion.quote)) { "Credentials cannot be saved as knowledge." }
        val existing = knowledge(proposal.intakeId).firstOrNull { it.proposalId == proposal.id }
        // Repeated confirmation must not overwrite a user's later correction or validity decision.
        if(existing != null) return@transaction existing.id
        val id = UUID.randomUUID().toString()
        val evidence = db.rawQuery("SELECT evidence FROM intake_proposals WHERE id=?",arrayOf(proposal.id)).use { it.moveToFirst();it.getString(0) }
        db.insertOrThrow("knowledge",null,ContentValues().apply {
            put("id",id);put("source_id",proposal.intakeId);put("proposal_id",proposal.id);put("proposal_revision",proposal.revision)
            put("text",fresh.suggestion.text);put("revision",1);put("status","active");put("expires_ms",expiresAt);put("evidence",evidence)
        })
        record(db,id,"Explicitly saved from reviewed fact")
        id
    }
    fun revise(entry: KnowledgeEntry, text: String, status: String, expiresAt: Long?, reason: String) = transaction { db ->
        require(text.trim().length in 1..600 && reason.trim().length in 1..200) { "Add a fact (up to 600 characters) and a reason (up to 200)." }
        require(status in setOf("active","needs_review","archived"))
        require(status != "active" || expiresAt == null || expiresAt > System.currentTimeMillis()) { "Choose a future validity date or archive this fact." }
        require(!MemorySafety.isCredential(text)) { "Credentials cannot be saved as knowledge." }
        require(db.update("knowledge",ContentValues().apply { put("text",text.trim());put("status",status);put("expires_ms",expiresAt);put("revision",entry.revision+1) },
            "id=? AND revision=?",arrayOf(entry.id,entry.revision.toString())) == 1) { "This fact changed. Reopen it before saving." }
        record(db,entry.id,reason.trim())
        Unit
    }
    fun history(id: String): List<KnowledgeRevision> = store.readableDatabase.rawQuery("SELECT revision,text,status,reason,changed_ms FROM knowledge_history WHERE knowledge_id=? ORDER BY revision DESC",arrayOf(id)).use { c -> buildList {
        while(c.moveToNext()) add(KnowledgeRevision(c.getInt(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4)))
    } }
    fun deleteKnowledge(id: String) { store.writableDatabase.delete("knowledge","id=?",arrayOf(id)) }
    fun invalidateProposal(proposalId: String) = transaction { db ->
        val entry = knowledge().firstOrNull { it.proposalId == proposalId } ?: return@transaction
        if(entry.status != "archived") {
            db.execSQL("UPDATE knowledge SET status='needs_review',revision=revision+1 WHERE id=?",arrayOf(entry.id))
            record(db,entry.id,"Source suggestion changed; review knowledge again")
        }
    }
    fun search(query: String, projectId: String? = null, onlyKnowledge: Boolean = false, now: Long = System.currentTimeMillis()): List<ContextHit> {
        val match = ContextSearchPolicy.match(query) ?: return emptyList()
        val args = mutableListOf(match,now.toString())
        val projectClause = if(projectId == null) "" else " AND EXISTS(SELECT 1 FROM project_sources ps WHERE ps.source_id=context_search.source_id AND ps.project_id=?)".also { args.add(projectId) }
        val knowledgeClause = if(onlyKnowledge) " AND kind='knowledge'" else ""
        return store.readableDatabase.rawQuery("""SELECT source_id,record_id,kind,title,body,start_char,end_char FROM context_search
            WHERE context_search MATCH ? AND EXISTS(SELECT 1 FROM sources s WHERE s.id=context_search.source_id)
            AND (kind<>'knowledge' OR EXISTS(SELECT 1 FROM knowledge k WHERE k.id=context_search.record_id AND k.status='active' AND (k.expires_ms IS NULL OR k.expires_ms>?)))
            $projectClause $knowledgeClause ORDER BY CASE WHEN kind='knowledge' THEN 0 ELSE 1 END,source_id,kind,CAST(start_char AS INTEGER) LIMIT 30""",args.toTypedArray()).use { c -> buildList {
                while(c.moveToNext()) add(ContextHit(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),if(c.getString(2)=="caption") c.getString(4).length else c.getInt(6)))
            } }
    }
}
