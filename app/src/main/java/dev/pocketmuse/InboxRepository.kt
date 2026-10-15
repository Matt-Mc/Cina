package dev.pocketmuse

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import org.json.JSONObject
import java.util.UUID

data class CapturedSource(
    val title: String, val mime: String, val original: ByteArray, val text: String,
    val origin: String, val extraction: String = "ready", val accompanyingText: String = ""
)
data class InboxItem(val id: String, val title: String, val mime: String, val capturedAt: Long,
    val state: String, val extraction: String, val duplicate: Boolean, val category: String = "")
data class InboxDetail(val item: InboxItem, val text: String, val origin: String, val accompanyingText: String)

/** Capture is a single transaction, including original bytes. No chat or model owns this data. */
class InboxRepository(private val store: LocalStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sources(id TEXT PRIMARY KEY, title TEXT NOT NULL, mime TEXT NOT NULL, captured_ms INTEGER NOT NULL, origin TEXT NOT NULL, sha256 TEXT NOT NULL, extraction TEXT NOT NULL, text TEXT NOT NULL, accompanying_text TEXT NOT NULL)")
            db.execSQL("CREATE INDEX sources_hash ON sources(sha256)")
            // Small chunks keep original files below Android CursorWindow's per-row limit.
            db.execSQL("CREATE TABLE source_bytes(source_id TEXT NOT NULL, ordinal INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(source_id,ordinal))")
            db.execSQL("CREATE TABLE source_passages(source_id TEXT NOT NULL, ordinal INTEGER NOT NULL, start_char INTEGER NOT NULL, end_char INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(source_id,ordinal))")
            db.execSQL("CREATE TABLE intake_items(id TEXT PRIMARY KEY, source_id TEXT NOT NULL UNIQUE, state TEXT NOT NULL DEFAULT 'captured', revision INTEGER NOT NULL DEFAULT 1, instruction TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '')")
            // Reserved for reviewed interpretation; capture never creates or executes proposals.
            db.execSQL("CREATE TABLE intake_proposals(id TEXT PRIMARY KEY, intake_id TEXT NOT NULL, revision INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, evidence TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending')")
        }
    }

    fun contains(id: String): Boolean = store.readableDatabase.rawQuery("SELECT 1 FROM intake_items WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    fun capture(id: String, source: CapturedSource) {
        require(id.isNotBlank())
        require(source.original.size <= CapturePolicy.MAX_BYTES)
        require(source.text.length <= CapturePolicy.MAX_TEXT && source.accompanyingText.length <= 20_000)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            // A restored share activity can safely finish the same request after process death.
            if (!contains(id)) {
                val hash = MessageDigest.getInstance("SHA-256").digest(source.original).joinToString("") { "%02x".format(it) }
                db.insertOrThrow("sources", null, ContentValues().apply {
                    put("id", id); put("title", source.title.take(160)); put("mime", source.mime)
                    put("captured_ms", System.currentTimeMillis()); put("origin", source.origin)
                    put("sha256", hash); put("extraction", source.extraction); put("text", source.text)
                    put("accompanying_text", source.accompanyingText)
                })
                var offset = 0
                while (offset < source.original.size) {
                    val end = minOf(offset + 64 * 1024, source.original.size)
                    db.insertOrThrow("source_bytes", null, ContentValues().apply {
                        put("source_id", id); put("ordinal", offset / (64 * 1024)); put("bytes", source.original.copyOfRange(offset, end))
                    })
                    offset = end
                }
                source.text.chunked(1200).forEachIndexed { index, passage ->
                    db.insertOrThrow("source_passages", null, ContentValues().apply {
                        put("source_id", id); put("ordinal", index); put("start_char", index * 1200)
                        put("end_char", index * 1200 + passage.length); put("body", passage)
                    })
                }
                db.insertOrThrow("intake_items", null, ContentValues().apply { put("id", id); put("source_id", id) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun items(): List<InboxItem> = store.readableDatabase.rawQuery("""
        SELECT s.id,s.title,s.mime,s.captured_ms,i.state,s.extraction,
        EXISTS(SELECT 1 FROM sources other WHERE other.sha256=s.sha256 AND other.id<>s.id),
        (SELECT payload FROM intake_proposals WHERE intake_id=i.id AND kind='filing' AND state='accepted' ORDER BY rowid LIMIT 1)
        FROM sources s JOIN intake_items i ON i.source_id=s.id ORDER BY s.captured_ms DESC,s.id
    """.trimIndent(), null).use { cursor -> buildList {
        while (cursor.moveToNext()) add(InboxItem(cursor.getString(0), cursor.getString(1), cursor.getString(2),
            cursor.getLong(3), cursor.getString(4), cursor.getString(5), cursor.getInt(6) != 0,
            if (cursor.isNull(7)) "" else JSONObject(cursor.getString(7)).getString("text")))
    } }

    fun detail(item: InboxItem): InboxDetail? = store.readableDatabase.rawQuery(
        "SELECT text,origin,accompanying_text FROM sources WHERE id=?", arrayOf(item.id)
    ).use { if (it.moveToFirst()) InboxDetail(item, it.getString(0), it.getString(1), it.getString(2)) else null }

    fun delete(id: String) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.delete("intake_proposals", "intake_id=?", arrayOf(id))
            db.delete("intake_items", "id=?", arrayOf(id))
            listOf("source_passages", "source_bytes").forEach { db.delete(it, "source_id=?", arrayOf(id)) }
            db.delete("sources", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun review(id: String): IntakeReview? {
        val db = store.readableDatabase
        val header = db.rawQuery("SELECT revision,state,error FROM intake_items WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) return null
            Triple(it.getInt(0), it.getString(1), it.getString(2))
        }
        val proposals = db.rawQuery("SELECT id,revision,kind,payload,evidence,state FROM intake_proposals WHERE intake_id=? ORDER BY rowid", arrayOf(id)).use { c -> buildList {
            while (c.moveToNext()) {
                val payload = JSONObject(c.getString(3)); val evidence = JSONObject(c.getString(4))
                add(IntakeProposal(c.getString(0), id, c.getInt(1), IntakeSuggestion(c.getString(2), payload.getString("text"),
                    evidence.getString("field"), evidence.getInt("start"), evidence.getInt("end"), evidence.getString("quote")),
                    c.getString(5), payload.optBoolean("edited"), payload.getString("model"), payload.getString("coverage")))
            }
        } }
        return IntakeReview(header.first, header.second, header.third, proposals)
    }

    fun saveInterpretation(id: String, expectedRevision: Int, result: InterpretationResult, model: String) {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val current = review(id) ?: error("This source was deleted.")
            require(current.revision == expectedRevision && current.proposals.isEmpty()) { "This item changed. Reopen it to see its latest review." }
            val source = items().firstOrNull { it.id == id }?.let(::detail) ?: error("This source was deleted.")
            require(result.suggestions.size <= 8)
            result.suggestions.forEach { suggestion ->
                val body = when (suggestion.field) { "text" -> source.text; "caption" -> source.accompanyingText; else -> error("Invalid source field") }
                require(suggestion.kind in InboxInterpretation.kinds && suggestion.text.length in 1..600)
                require(suggestion.start >= 0 && suggestion.end <= body.length && suggestion.end > suggestion.start)
                require(suggestion.quote.length in 4..400 && body.substring(suggestion.start, suggestion.end) == suggestion.quote) { "The evidence does not match the saved source." }
                db.insertOrThrow("intake_proposals", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("intake_id", id); put("revision", 1); put("kind", suggestion.kind)
                    put("payload", JSONObject().put("text", suggestion.text).put("edited", false).put("model", model).put("coverage", result.coverage).put("policy_version", 1).toString())
                    put("evidence", JSONObject().put("field", suggestion.field).put("start", suggestion.start).put("end", suggestion.end).put("quote", suggestion.quote).toString())
                })
            }
            db.execSQL("UPDATE intake_items SET revision=revision+1,state=?,error='' WHERE id=?", arrayOf(if (result.suggestions.isEmpty()) "reviewed" else "needs_review", id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun interpretationFailed(id: String, expectedRevision: Int, message: String) {
        store.writableDatabase.update("intake_items", ContentValues().apply { put("error", message.take(500)) },
            "id=? AND revision=?", arrayOf(id, expectedRevision.toString()))
    }

    /** Review changes only metadata. Accepting even an action never invokes a tool. */
    fun updateProposal(id: String, proposalId: String, expectedRevision: Int, text: String, state: String) {
        require(text.trim().length in 1..600) { "Use 1–600 characters for a suggestion." }
        require(state in setOf("pending", "accepted", "deferred", "discarded"))
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val old = review(id)?.proposals?.singleOrNull { it.id == proposalId } ?: error("This suggestion no longer exists.")
            require(old.revision == expectedRevision) { "This suggestion changed. Reopen it before editing." }
            val payload = db.rawQuery("SELECT payload FROM intake_proposals WHERE id=?", arrayOf(proposalId)).use { it.moveToFirst(); JSONObject(it.getString(0)) }
            if (old.state != state || old.suggestion.text != text.trim()) ContextRepository(store).invalidateProposal(proposalId)
            payload.put("text", text.trim()).put("edited", old.edited || text.trim() != old.suggestion.text)
            db.update("intake_proposals", ContentValues().apply { put("payload", payload.toString()); put("state", state); put("revision", expectedRevision + 1) }, "id=?", arrayOf(proposalId))
            val unresolved = review(id)!!.proposals.any { it.state == "pending" || it.state == "deferred" }
            db.execSQL("UPDATE intake_items SET revision=revision+1,state=?,error='' WHERE id=?", arrayOf(if (unresolved) "needs_review" else "reviewed", id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
