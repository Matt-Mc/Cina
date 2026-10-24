package dev.pocketmuse

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File
import java.util.UUID

class ContextLibraryFlowTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val id=UUID.randomUUID().toString()
    private val project="Cedar ${id.take(8)}"
    private val text="The Cedar reference color is blue."
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun seedReviewedSource() {
        LocalStore(base).use { store ->
            val inbox=InboxRepository(store)
            inbox.capture(id,CapturedSource("Cedar test $id","text/plain",text.toByteArray(),text,"Test"))
            inbox.saveInterpretation(id,1,InterpretationResult(listOf(IntakeSuggestion("fact",text,"text",0,text.length,text)),"Test source"),"fixture")
            val proposal=inbox.review(id)!!.proposals.single()
            inbox.updateProposal(id,proposal.id,proposal.revision,text,"accepted")
        }
    }
    @After fun removeOnlyFixtures() {
        LocalStore(base).use { store ->
            InboxRepository(store).delete(id)
            val context=ContextRepository(store)
            context.projects().filter { it.name==project }.forEach { context.deleteProject(it.id) }
        }
    }
    @Test fun createProjectPromoteCorrectAndRetrieveWithSourceLink() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Inbox").performClick()
        compose.onNodeWithText("Knowledge & projects").performClick()
        compose.onNodeWithText("Storage & backup").performClick()
        compose.onNodeWithText("Storage & backup").assertIsDisplayed()
        compose.onNodeWithText("Back to knowledge & projects").performClick()
        compose.onNodeWithText("Projects").performClick()
        compose.onNodeWithText("New project").performClick()
        compose.onNodeWithText("Project name").performTextInput(project)
        compose.onNodeWithText("Save project").performClick()
        compose.waitUntil(10_000) { LocalStore(base).use { ContextRepository(it).projects().any { p -> p.name==project } } }
        compose.onNodeWithText("Back to Inbox").performClick()
        compose.onAllNodesWithText("Open")[0].performScrollTo().performClick()
        compose.onNodeWithContentDescription("Link to $project").performScrollTo().performClick()
        compose.onNodeWithText("Save as knowledge").performScrollTo().performClick()
        compose.onNodeWithText("Save knowledge").performClick()
        compose.waitUntil(10_000) { LocalStore(base).use { ContextRepository(it).knowledge(id).size==1 } }
        compose.onNodeWithText("Knowledge & projects").performScrollTo().performClick()
        compose.onNodeWithText("Knowledge").performClick()
        compose.onNodeWithText("Correct / review").performScrollTo().performClick()
        compose.onNodeWithText("Fact").performTextReplacement("The Cedar reference color is green.")
        compose.onNodeWithText("Reason for this revision").performTextInput("I corrected the reference color")
        compose.onNodeWithText("Save revision").performClick()
        compose.waitUntil(10_000) { LocalStore(base).use { ContextRepository(it).knowledge(id).singleOrNull()?.revision==2 } }
        compose.onNodeWithText("Find").performClick()
        compose.onNodeWithText("Search saved context").performTextReplacement("green")
        compose.onNodeWithContentDescription("Search current knowledge only").performClick()
        compose.onNodeWithText("Find matches").performClick()
        lateinit var contextModel: ContextViewModel
        compose.runOnIdle { contextModel = ViewModelProvider(compose.activity)[ContextViewModel::class.java] }
        compose.waitUntil(10_000) { contextModel.hits.value.any { it.body == "The Cedar reference color is green." } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("The Cedar reference color is green."))
        compose.onNodeWithText("The Cedar reference color is green.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open source").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(base.getExternalFilesDir(null),"context-search.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        compose.onNodeWithText("Open source").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cedar test $id",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Captured content").performScrollTo().assertIsDisplayed()
        // A source link from the new Library entry must also work when Inbox's nested library was open.
        compose.onNodeWithText("Knowledge & projects").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Knowledge & projects").performClick()
        compose.onNodeWithText("Back to Library").assertIsDisplayed()
        // This destination restores its prior search draft when the navigation state is retained.
        compose.onNodeWithText("Search saved context").performTextReplacement("green")
        compose.onNodeWithText("Find matches").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { contextModel.hits.value.any { it.body == "The Cedar reference color is green." } }
        } catch(error: Exception) {
            throw AssertionError("Search state: ${contextModel.message.value}; hits=${contextModel.hits.value.map { it.body }}; UI=${compose.onRoot().printToString()}", error)
        }
        // The keyboard can leave results outside LazyColumn's composed viewport.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open source"))
        compose.onNodeWithText("Open source").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cedar test $id",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Captured content").performScrollTo().assertIsDisplayed()
    }
}
