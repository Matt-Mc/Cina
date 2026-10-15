package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryStorageTest {
    private fun fixture(block: (LocalStore,InboxRepository,ContextRepository,LibraryStorage) -> Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"backup-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name: String)=file }
        try { LocalStore(context).use { block(it,InboxRepository(it),ContextRepository(it),LibraryStorage(it)) } }
        finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun seed(inbox: InboxRepository,context: ContextRepository): String {
        val text="The key is blue."
        inbox.capture("one",CapturedSource("A saved source","text/plain",text.toByteArray(),text,"Test",accompanyingText="Ottawa"))
        inbox.saveInterpretation("one",1,InterpretationResult(listOf(IntakeSuggestion("fact",text,"text",0,text.length,text)),"Full source"),"Test")
        val first=inbox.review("one")!!.proposals.single()
        inbox.updateProposal("one",first.id,first.revision,text,"accepted")
        val id=context.promote(inbox.review("one")!!.proposals.single())
        context.revise(context.knowledge().single(),"The key is green.","active",null,"Corrected colour")
        val project=context.saveProject("Home");context.linkSource("one",project,true)
        return id
    }
    @Test fun encryptedRestoreRecoversOriginalsReviewsHistoryMembershipAndSearch()=fixture { store,inbox,context,storage ->
        val id=seed(inbox,context)
        val original=storage.export()
        val encrypted=LibraryBackupCrypto.encrypt(original,"long test passphrase".toCharArray())
        inbox.delete("one");context.deleteProject(context.projects().single().id)
        store.writableDatabase.execSQL("INSERT INTO notes(title,body) VALUES('Unrelated','Keep me')")
        val payload=LibraryBackupCrypto.decrypt(encrypted.inputStream(),"long test passphrase".toCharArray())
        val preview=storage.previewRestore(payload)
        assertEquals(1L,preview.size.sources);assertTrue(inbox.items().isEmpty())
        storage.restore(preview);preview.clear()
        assertArrayEquals(original,storage.export())
        assertEquals("accepted",inbox.review("one")!!.proposals.single().state)
        assertEquals(2,context.history(id).size)
        assertEquals(setOf(context.projects().single().id),context.sourceProjects("one"))
        assertEquals(1,context.search("green",onlyKnowledge=true).size)
        assertTrue(context.search("blue",onlyKnowledge=true).isEmpty())
        assertTrue(context.search("Ottawa").isNotEmpty())
        assertEquals("Keep me",store.readableDatabase.rawQuery("SELECT body FROM notes",null).use { it.moveToFirst();it.getString(0) })
        inbox.delete("one");assertTrue(context.search("green").isEmpty());assertTrue(context.history(id).isEmpty())
    }
    @Test fun staleRestoreAndCleanupPreviewsCannotOverwriteEdits()=fixture { _,inbox,context,storage ->
        seed(inbox,context)
        val restore=storage.previewRestore(storage.export())
        val cleanup=storage.previewRetention(30,System.currentTimeMillis()+40L*86400000)
        context.saveProject("New project")
        assertTrue(runCatching { storage.restore(restore) }.isFailure)
        assertTrue(runCatching { storage.deleteRetained(cleanup) }.isFailure)
        assertEquals(2,context.projects().size);assertEquals(1,inbox.items().size)
        restore.clear()
    }
    @Test fun cleanupDeletesDependentsButKeepsProjectsAndNewerSources()=fixture { store,inbox,context,storage ->
        val id=seed(inbox,context)
        val now=System.currentTimeMillis()
        store.writableDatabase.execSQL("UPDATE sources SET captured_ms=? WHERE id='one'",arrayOf(now-100L*86400000))
        inbox.capture("new",CapturedSource("Recent","text/plain","new".toByteArray(),"new","Test"))
        val preview=storage.previewRetention(90,now)
        assertEquals(listOf("one"),preview.ids);assertEquals(1L,preview.facts)
        storage.deleteRetained(preview)
        assertEquals(listOf("new"),inbox.items().map { it.id });assertEquals(1,context.projects().size)
        assertTrue(context.knowledge().isEmpty());assertTrue(context.history(id).isEmpty());assertTrue(context.sourceProjects("one").isEmpty())
        listOf("source_bytes","source_passages","intake_items","intake_proposals").forEach { table ->
            val field=if(table=="intake_proposals") "intake_id" else "source_id"
            assertEquals(0,store.readableDatabase.rawQuery("SELECT count(*) FROM $table WHERE $field='one'",null).use { it.moveToFirst();it.getInt(0) })
        }
    }
    @Test fun invalidReferencesHashesEvidenceAndVersionsNeverChangeLiveData()=fixture { store,inbox,context,storage ->
        seed(inbox,context)
        val good=storage.export()
        val baseline=storage.previewRestore(good)
        listOf(
            "UPDATE source_bytes SET bytes=x'00'",
            "UPDATE knowledge SET source_id='missing'",
            "UPDATE knowledge SET evidence='{\"field\":\"text\",\"start\":0,\"end\":4,\"quote\":\"fake\"}'",
            "UPDATE source_passages SET body='wrong'"
        ).forEach { sql ->
            val db=store.writableDatabase;db.beginTransaction()
            val broken=try { db.execSQL(sql);storage.export() } finally { db.endTransaction() }
            assertTrue(runCatching { storage.previewRestore(broken) }.isFailure)
            assertArrayEquals(good,storage.export())
        }
        assertTrue(runCatching { storage.previewRestore(good.copyOf(good.size-1)) }.isFailure)
        assertTrue(runCatching { storage.previewRestore(good+byteArrayOf(0)) }.isFailure)
        val unsupported=good.copyOf();unsupported[3]=2
        assertTrue(runCatching { storage.previewRestore(unsupported) }.isFailure)
        storage.restore(baseline);baseline.clear()
    }
    @Test fun failedInsertionRollsBackReplacement()=fixture { store,inbox,context,storage ->
        seed(inbox,context)
        val good=storage.export();val preview=storage.previewRestore(good)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON source_bytes BEGIN SELECT RAISE(ABORT,'Injected failure'); END")
        assertTrue(runCatching { storage.restore(preview) }.isFailure)
        assertArrayEquals(good,storage.export());assertTrue(context.search("green",onlyKnowledge=true).isNotEmpty())
        preview.clear()
    }
    @Test fun emptyLibraryCanBeExportedAndRestored()=fixture { _,inbox,context,storage ->
        val empty=storage.export();seed(inbox,context)
        val preview=storage.previewRestore(empty);storage.restore(preview);preview.clear()
        assertEquals(LibrarySize(0,0,0,0),storage.size());assertTrue(context.search("blue").isEmpty())
    }
}
