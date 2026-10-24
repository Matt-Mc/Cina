package dev.pocketmuse

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

internal fun removeContextSchemaForMigrationTest(db: SQLiteDatabase) {
    removeWorkSchemaForMigrationTest(db)
    listOf("context_source_insert","context_passage_insert","context_source_delete","context_knowledge_insert","context_knowledge_update","context_knowledge_delete").forEach { db.execSQL("DROP TRIGGER $it") }
    listOf("context_search","knowledge_history","knowledge","project_history","project_sources","context_projects").forEach { db.execSQL("DROP TABLE $it") }
}

class ContextRepositoryTest {
    private fun fixture(block: (ContextWrapper,LocalStore,InboxRepository,ContextRepository) -> Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"context-${System.nanoTime()}.db")
        val context=object:ContextWrapper(base) { override fun getDatabasePath(name: String)=file }
        try { LocalStore(context).use { block(context,it,InboxRepository(it),ContextRepository(it)) } }
        finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun source(inbox: InboxRepository,id: String,text: String,caption: String="") {
        inbox.capture(id,CapturedSource("Source $id","text/plain",text.toByteArray(),text,"Test",accompanyingText=caption))
    }
    private fun fact(inbox: InboxRepository,id: String,text: String): IntakeProposal {
        val suggestion=IntakeSuggestion("fact",text,"text",0,text.length,text)
        inbox.saveInterpretation(id,1,InterpretationResult(listOf(suggestion),"Whole test source"),"fixture")
        val proposal=inbox.review(id)!!.proposals.single()
        inbox.updateProposal(id,proposal.id,proposal.revision,text,"accepted")
        return inbox.review(id)!!.proposals.single()
    }
    @Test fun migrationBackfillsExistingSourcesAndPreservesReviews() = fixture { context,store,inbox,_ ->
        source(inbox,"old","Maple budget approved.","Reviewed in Ottawa")
        fact(inbox,"old","Maple budget approved.")
        removeContextSchemaForMigrationTest(store.writableDatabase)
        store.writableDatabase.version=8;store.close()
        LocalStore(context).use { upgraded ->
            assertEquals(12,upgraded.readableDatabase.version)
            assertEquals("accepted",InboxRepository(upgraded).review("old")!!.proposals.single().state)
            assertTrue(ContextRepository(upgraded).search("Maple").any { it.sourceId=="old" })
            assertTrue(ContextRepository(upgraded).search("Ottawa").any { it.kind=="caption" })
            assertTrue(ContextRepository(upgraded).knowledge().isEmpty())
        }
    }
    @Test fun promotionIsExplicitIdempotentAndRevisionChecked() = fixture { _,store,inbox,context ->
        source(inbox,"one","The spare key is blue.")
        val proposal=fact(inbox,"one","The spare key is blue.")
        assertTrue(context.knowledge().isEmpty())
        val id=context.promote(proposal)
        assertEquals(id,context.promote(proposal));assertEquals(1,context.history(id).size)
        assertEquals("one",context.knowledge().single().sourceId)
        inbox.updateProposal("one",proposal.id,proposal.revision,"The spare key is green.","pending")
        assertEquals("needs_review",context.knowledge().single().status)
        assertTrue(context.search("blue",onlyKnowledge=true).isEmpty())
        assertTrue(runCatching { context.promote(proposal) }.isFailure)
        assertTrue(store.memories().isEmpty());assertTrue(store.notes().isEmpty())
    }
    @Test fun correctionsReplaceCurrentSearchAndPreserveHistoryAfterReopen() = fixture { base,store,inbox,context ->
        source(inbox,"one","The key is blue.")
        val proposal=fact(inbox,"one","The key is blue.")
        val id=context.promote(proposal)
        val old=context.knowledge().single()
        context.revise(old,"The key is green.","active",null,"I moved the key")
        assertTrue(context.search("blue",onlyKnowledge=true).isEmpty())
        assertEquals("The key is green.",context.search("green",onlyKnowledge=true).single().body)
        assertTrue(context.search("blue").all { it.kind!="knowledge" })
        assertEquals(id,context.promote(proposal))
        assertEquals("The key is green.",context.knowledge().single().text)
        assertTrue(runCatching { context.revise(old,"Stale","active",null,"Old edit") }.isFailure)
        LocalStore(base).use { reopened ->
            val repo=ContextRepository(reopened)
            assertEquals(listOf("The key is green.","The key is blue."),repo.history(id).map { it.text })
            assertEquals("The key is green.",repo.search("green",onlyKnowledge=true).single().body)
        }
        assertEquals(12,store.readableDatabase.version)
    }
    @Test fun expiryArchiveAndConflictsAreExcludedFromCurrentRetrieval() = fixture { _,_,inbox,context ->
        source(inbox,"one","I am staying in Ottawa.")
        val expiry=System.currentTimeMillis()+100_000
        context.promote(fact(inbox,"one","I am staying in Ottawa."),expiry)
        assertTrue(context.search("Ottawa",onlyKnowledge=true,now=expiry).isEmpty())
        assertEquals(1,context.search("Ottawa",onlyKnowledge=true,now=expiry-1).size)
        val entry=context.knowledge().single()
        context.revise(entry,entry.text,"needs_review",null,"Conflicting information")
        assertTrue(context.search("Ottawa",onlyKnowledge=true).isEmpty())
        val changed=context.knowledge().single()
        context.revise(changed,changed.text,"archived",null,"Trip ended")
        assertTrue(context.search("Ottawa",onlyKnowledge=true).isEmpty())
    }
    @Test fun projectsFilterAcrossSourcesAndCanBeUnlinkedWithoutDeletingContent() = fixture { _,_,inbox,context ->
        source(inbox,"one","Maple budget is 200.");source(inbox,"two","Maple deadline is Friday.");source(inbox,"three","Maple unrelated note.")
        val project=context.saveProject("Maple")
        context.linkSource("one",project,true);context.linkSource("two",project,true)
        assertEquals(setOf("one","two"),context.search("Maple",project).map { it.sourceId }.toSet())
        context.linkSource("two",project,false)
        assertEquals(setOf("one"),context.search("Maple",project).map { it.sourceId }.toSet())
        val old=context.projects().single()
        context.saveProject("Maple house",old)
        assertTrue(runCatching { context.saveProject("Stale",old) }.isFailure)
        context.deleteProject(project)
        assertEquals(3,inbox.items().size);assertTrue(context.sourceProjects("one").isEmpty())
    }
    @Test fun deletingSourceRemovesDerivedSearchKnowledgeAndHistory() = fixture { _,store,inbox,context ->
        source(inbox,"one","Unique evidence for deletion.")
        val id=context.promote(fact(inbox,"one","Unique evidence for deletion."))
        context.linkSource("one",context.saveProject("Project"),true)
        inbox.delete("one")
        assertTrue(context.search("Unique").isEmpty());assertTrue(context.knowledge().isEmpty());assertTrue(context.history(id).isEmpty())
        assertTrue(context.sourceProjects("one").isEmpty());assertEquals(1,context.projects().size)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM context_search",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
    }
    @Test fun deletedKnowledgeDoesNotRemoveOriginalEvidence() = fixture { _,_,inbox,context ->
        source(inbox,"one","I prefer tea without sugar.")
        val id=context.promote(fact(inbox,"one","I prefer tea without sugar."))
        context.deleteKnowledge(id)
        assertTrue(context.search("tea",onlyKnowledge=true).isEmpty())
        assertTrue(context.search("tea").any { it.kind=="passage" })
        assertTrue(context.history(id).isEmpty())
    }
    @Test fun unicodeQueriesAndHostileSyntaxDoNotExpandScope() = fixture { _,_,inbox,context ->
        source(inbox,"one","Café 茶 and tea.");source(inbox,"two","Private coffee.")
        assertEquals(setOf("one"),context.search("café").map { it.sourceId }.toSet())
        assertTrue(context.search("tea OR coffee").isEmpty())
        assertTrue(context.search("body:private*").isEmpty())
        assertTrue(context.search("\"; DROP TABLE sources; --").isEmpty())
        assertEquals(2,inbox.items().size)
    }
}
