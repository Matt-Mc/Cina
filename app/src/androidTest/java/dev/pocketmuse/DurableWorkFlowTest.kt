package dev.pocketmuse

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File
import java.util.UUID

class DurableWorkFlowTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val sourceId=UUID.randomUUID().toString()
    private val title="Packing ${sourceId.take(8)}"
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun seed() {
        LocalStore(base).use { store ->
            val inbox=InboxRepository(store);val text="Write a packing note."
            inbox.capture(sourceId,CapturedSource(title,"text/plain",text.toByteArray(),text,"Test"))
            inbox.saveInterpretation(sourceId,1,InterpretationResult(listOf(IntakeSuggestion("action",text,"text",0,text.length,text)),"Complete source"),"Fixture")
            val p=inbox.review(sourceId)!!.proposals.single();inbox.updateProposal(sourceId,p.id,p.revision,text,"accepted")
        }
    }
    @After fun cleanup() {
        LocalStore(base).use { store ->
            WorkCoordinator(store).tasks().filter { it.sourceId==sourceId }.forEach { task ->
                if(task.state !in setOf("completed","cancelled")) WorkCoordinator(store).control(task,"cancel")
                task.steps.forEach { it.resultId?.let(store::deleteNote) }
                store.writableDatabase.delete("work_steps","work_id=?",arrayOf(task.id))
                store.writableDatabase.delete("durable_work_events","work_id=?",arrayOf(task.id))
                store.writableDatabase.delete("durable_work","id=?",arrayOf(task.id))
            }
            InboxRepository(store).delete(sourceId)
        }
    }
    @Test fun inboxTaskSurvivesRecreationAndWorkerRunsOnlyAfterExactApproval() {
        compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithText("Library").performClick();compose.onNodeWithText("Inbox").performClick()
        compose.onAllNodesWithText("Open")[0].performScrollTo().performClick()
        compose.onNodeWithText("Create task…").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create a local task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Task title").performScrollTo().performTextReplacement(title)
        compose.onNodeWithText("Note content").performScrollTo().performTextReplacement("Bring the blue bag and charger.")
        compose.onNodeWithText("Add step").performScrollTo().performClick()
        compose.onNodeWithText("Task title").performScrollTo().performTextReplacement("$title second")
        compose.onNodeWithText("Note content").performScrollTo().performTextReplacement("Check the weather before leaving.")
        compose.onNodeWithText("Save task for review").performClick()
        compose.waitUntil(10_000) { LocalStore(base).use { WorkCoordinator(it).tasks().any { task -> task.sourceId==sourceId } } }
        LocalStore(base).use { Assert.assertTrue(it.notes().none { note -> note.title==title }) }
        compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithText("Work").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("All work & filters").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("All work & filters").performClick();compose.onNodeWithText("Actions").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Review action").performScrollTo().performClick()
        compose.onNodeWithText("Bring the blue bag and charger.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check the weather before leaving.").performScrollTo().assertIsDisplayed()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap -> File(base.getExternalFilesDir(null),"work-approval.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        compose.onNodeWithText("Approve once").performClick()
        // Leave Tasks: the actual WorkManager worker, not a chat/ViewModel executor, performs the write.
        compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithText("Chat").performClick()
        compose.waitUntil(30_000) { LocalStore(base).use { WorkCoordinator(it).tasks().any { task -> task.sourceId==sourceId && task.state=="completed" } } }
        LocalStore(base).use { store ->
            val notes=store.notes().filter { it.title==title };Assert.assertEquals(1,notes.size);Assert.assertEquals("Bring the blue bag and charger.",notes.single().body)
            Assert.assertEquals(1,store.notes().count { it.title=="$title second" })
        }
        compose.onNodeWithContentDescription("Open menu").performClick();compose.onNodeWithText("Work").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.onNodeWithText("Task history").performScrollTo().performClick()
        compose.onAllNodes(hasText("Verified note #",substring=true) and hasAnyAncestor(isDialog())).assertCountEquals(2)
    }
}
