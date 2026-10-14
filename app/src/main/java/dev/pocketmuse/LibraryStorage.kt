package dev.pocketmuse

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONObject

internal data class LibrarySize(val sources: Long, val facts: Long, val projects: Long, val originalBytes: Long)
internal class RestorePreview internal constructor(internal val payload: ByteArray, internal val fingerprint: ByteArray, val size: LibrarySize) {
    fun clear() = payload.fill(0)
}
internal data class RetentionPreview(val ids: List<String>, val titles: List<String>, val facts: Long, internal val fingerprint: ByteArray)

/** Only the explicit library tables are portable. No SQL, paths, tools or jobs come from a backup. */
internal class LibraryStorage(private val store: LocalStore) {
    companion object {
        private val tables = listOf("sources","source_bytes","source_passages","intake_items","intake_proposals",
            "context_projects","project_sources","project_history","knowledge","knowledge_history")
        private const val MAX_ROWS = 100_000
    }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db=store.writableDatabase; db.beginTransaction()
        try { val result=block(db); db.setTransactionSuccessful(); return result } finally { db.endTransaction() }
    }
    private fun count(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql,null).use { it.moveToFirst();it.getLong(0) }
    private fun size(db: SQLiteDatabase) = LibrarySize(count(db,"SELECT count(*) FROM sources"),count(db,"SELECT count(*) FROM knowledge"),
        count(db,"SELECT count(*) FROM context_projects"),count(db,"SELECT coalesce(sum(length(bytes)),0) FROM source_bytes"))
    fun size() = size(store.readableDatabase)
    private fun columns(db: SQLiteDatabase, table: String): List<Pair<String,String>> = db.rawQuery("PRAGMA table_info($table)",null).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(1) to c.getString(2)) }
    }
    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) { out.writeInt(bytes.size);out.write(bytes) }
    private fun write(db: SQLiteDatabase, output: OutputStream) {
        val out=DataOutputStream(output);out.writeInt(1)
        tables.forEach { table ->
            db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { c ->
                out.writeInt(c.count)
                while(c.moveToNext()) for(i in 0 until c.columnCount) {
                    out.writeByte(c.getType(i))
                    when(c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> out.writeLong(c.getLong(i))
                        Cursor.FIELD_TYPE_STRING -> writeBytes(out,c.getString(i).toByteArray(Charsets.UTF_8))
                        Cursor.FIELD_TYPE_BLOB -> writeBytes(out,c.getBlob(i))
                        else -> error("Unsupported library value.")
                    }
                }
            }
        }
        out.flush()
    }
    private fun fingerprint(db: SQLiteDatabase): ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        write(db,DigestOutputStream(object:OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray,off: Int,len: Int) {} },digest))
        return digest.digest()
    }
    fun export(): ByteArray = transaction { db ->
        val bytes=ByteArrayOutputStream()
        val limited=object:OutputStream() {
            override fun write(b: Int) { require(bytes.size()<LibraryBackupCrypto.MAX_PAYLOAD) { "This library exceeds the 32 MiB backup limit." };bytes.write(b) }
            override fun write(b: ByteArray,off: Int,len: Int) { require(bytes.size().toLong()+len<=LibraryBackupCrypto.MAX_PAYLOAD) { "This library exceeds the 32 MiB backup limit." };bytes.write(b,off,len) }
        }
        write(db,limited);bytes.toByteArray()
    }
    private fun readBytes(input: DataInputStream): ByteArray {
        val size=input.readInt();require(size in 0..1_048_576 && size<=input.available()) { "Invalid backup field length." }
        return ByteArray(size).also(input::readFully)
    }
    private fun load(db: SQLiteDatabase, payload: ByteArray) {
        require(payload.size<=LibraryBackupCrypto.MAX_PAYLOAD)
        val input=DataInputStream(ByteArrayInputStream(payload));require(input.readInt()==1) { "Unsupported library version." }
        var total=0
        tables.forEach { table ->
            val schema=columns(db,table)
            val rows=input.readInt();require(rows in 0..MAX_ROWS && total+rows<=MAX_ROWS) { "Backup has too many records." };total+=rows
            repeat(rows) {
                val values=ContentValues()
                schema.forEach { (name,type) ->
                    when(val kind=input.readUnsignedByte()) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(name)
                        Cursor.FIELD_TYPE_INTEGER -> { require(type=="INTEGER");values.put(name,input.readLong()) }
                        Cursor.FIELD_TYPE_STRING -> { require(type=="TEXT");values.put(name,Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(readBytes(input))).toString()) }
                        Cursor.FIELD_TYPE_BLOB -> { require(type=="BLOB");values.put(name,readBytes(input)) }
                        else -> error("Invalid backup field type $kind.")
                    }
                }
                db.insertOrThrow(table,null,values)
            }
        }
        require(input.read()==-1) { "Unexpected backup data." }
    }
    private fun validate(db: SQLiteDatabase) {
        fun none(query: String) { require(count(db,"SELECT count(*) FROM ($query)")==0L) { "Backup contains invalid or missing linked records." } }
        listOf("source_bytes","source_passages","intake_items","knowledge","project_sources").forEach { table ->
            none("SELECT 1 FROM $table c WHERE NOT EXISTS(SELECT 1 FROM sources s WHERE s.id=c.source_id)")
        }
        none("SELECT 1 FROM sources s WHERE NOT EXISTS(SELECT 1 FROM intake_items i WHERE i.id=s.id AND i.source_id=s.id)")
        none("SELECT 1 FROM intake_items WHERE id<>source_id OR revision<1 OR state NOT IN ('captured','needs_review','reviewed')")
        none("SELECT 1 FROM intake_proposals p WHERE revision<1 OR state NOT IN ('pending','accepted','deferred','discarded') OR NOT EXISTS(SELECT 1 FROM intake_items i WHERE i.id=p.intake_id)")
        listOf("project_sources","project_history").forEach { table -> none("SELECT 1 FROM $table c WHERE NOT EXISTS(SELECT 1 FROM context_projects p WHERE p.id=c.project_id)") }
        none("SELECT 1 FROM knowledge k WHERE revision<1 OR proposal_revision<1 OR status NOT IN ('active','needs_review','archived') OR NOT EXISTS(SELECT 1 FROM intake_proposals p WHERE p.id=k.proposal_id AND p.intake_id=k.source_id AND p.kind='fact' AND p.revision>=k.proposal_revision AND p.evidence=k.evidence)")
        none("SELECT 1 FROM knowledge_history h WHERE revision<1 OR status NOT IN ('active','needs_review','archived') OR NOT EXISTS(SELECT 1 FROM knowledge k WHERE k.id=h.knowledge_id AND k.revision>=h.revision)")
        none("SELECT 1 FROM context_projects p WHERE revision<1 OR length(name)<1 OR length(name)>80 OR NOT EXISTS(SELECT 1 FROM project_history h WHERE h.project_id=p.id AND h.revision=p.revision AND h.name=p.name)")
        none("SELECT 1 FROM knowledge k WHERE NOT EXISTS(SELECT 1 FROM knowledge_history h WHERE h.knowledge_id=k.id AND h.revision=k.revision AND h.text=k.text AND h.status=k.status AND h.expires_ms IS k.expires_ms)")
        db.rawQuery("SELECT id,text,accompanying_text,sha256,title FROM sources",null).use { c -> while(c.moveToNext()) {
            val id=c.getString(0);val text=c.getString(1);val caption=c.getString(2)
            require(id.isNotBlank() && text.length<=CapturePolicy.MAX_TEXT && caption.length<=20_000 && c.getString(4).length<=160)
            val hash=MessageDigest.getInstance("SHA-256");var bytes=0;var ordinal=0
            db.rawQuery("SELECT ordinal,bytes FROM source_bytes WHERE source_id=? ORDER BY ordinal",arrayOf(id)).use { b -> while(b.moveToNext()) {
                val chunk=b.getBlob(1);require(b.getInt(0)==ordinal++ && chunk.size in 1..65536);bytes+=chunk.size;require(bytes<=CapturePolicy.MAX_BYTES);hash.update(chunk)
            } }
            require(hash.digest().joinToString("") { "%02x".format(it) }==c.getString(3)) { "Original file checksum failed." }
            var offset=0;ordinal=0
            db.rawQuery("SELECT ordinal,start_char,end_char,body FROM source_passages WHERE source_id=? ORDER BY ordinal",arrayOf(id)).use { p -> while(p.moveToNext()) {
                val body=p.getString(3);require(p.getInt(0)==ordinal++ && p.getInt(1)==offset && body.isNotEmpty() && body.length<=1200)
                offset+=body.length;require(p.getInt(2)==offset && offset<=text.length && text.substring(p.getInt(1),offset)==body)
            } }
            require(offset==text.length)
            fun evidence(raw: String) {
                val e=JSONObject(raw);val body=when(e.getString("field")) { "text" -> text;"caption" -> caption;else -> error("Invalid evidence field.") }
                val start=e.getInt("start");val end=e.getInt("end");val quote=e.getString("quote")
                require(start>=0 && end>start && end<=body.length && quote.length in 4..400 && body.substring(start,end)==quote) { "Backup evidence does not match its source." }
            }
            db.rawQuery("SELECT kind,payload,evidence FROM intake_proposals WHERE intake_id=?",arrayOf(id)).use { p -> while(p.moveToNext()) {
                require(p.getString(0) in InboxInterpretation.kinds)
                val body=JSONObject(p.getString(1));require(body.getString("text").length in 1..600);body.getString("model");body.getString("coverage");evidence(p.getString(2))
            } }
            db.rawQuery("SELECT text,evidence FROM knowledge WHERE source_id=?",arrayOf(id)).use { k -> while(k.moveToNext()) {
                require(k.getString(0).length in 1..600 && !MemorySafety.isCredential(k.getString(0)));evidence(k.getString(1))
            } }
        } }
    }
    fun previewRestore(payload: ByteArray): RestorePreview {
        val summary=SQLiteDatabase.create(null).use { stage ->
            InboxRepository.createTables(stage);ContextRepository.createTables(stage);load(stage,payload);validate(stage);size(stage)
        }
        return transaction { RestorePreview(payload.copyOf(),fingerprint(it),summary) }
    }
    fun restore(preview: RestorePreview) = transaction { db ->
        require(MessageDigest.isEqual(fingerprint(db),preview.fingerprint)) { "Library changed after the preview. Open the backup again." }
        // Validate again inside the operation; any insertion/validation failure rolls back the entire replacement.
        tables.reversed().forEach { db.delete(it,null,null) }
        db.delete("context_search",null,null)
        load(db,preview.payload);validate(db)
    }
    fun previewRetention(days: Int, now: Long=System.currentTimeMillis()): RetentionPreview = transaction { db ->
        require(days in listOf(30,90,365))
        val ids=mutableListOf<String>();val titles=mutableListOf<String>();var facts=0L
        db.rawQuery("SELECT id,title,(SELECT count(*) FROM knowledge k WHERE k.source_id=s.id) FROM sources s WHERE captured_ms<? ORDER BY captured_ms,id",arrayOf((now-days*86400000L).toString())).use { c -> while(c.moveToNext()) { ids.add(c.getString(0));titles.add(c.getString(1));facts+=c.getLong(2) } }
        RetentionPreview(ids,titles,facts,fingerprint(db))
    }
    fun deleteRetained(preview: RetentionPreview) = transaction { db ->
        require(MessageDigest.isEqual(fingerprint(db),preview.fingerprint)) { "Library changed after the preview. Preview cleanup again." }
        preview.ids.forEach { InboxRepository(store).delete(it) }
    }
}
