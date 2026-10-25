package dev.pocketmuse

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IntakeReviewCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun editsRequireReviewAndEvidenceRemainsVisible() {
        val value = mutableStateOf(IntakeProposal("p", "source", 1,
            IntakeSuggestion("action", "Send draft", "text", 0, 14, "Send the draft"), "pending", false, "test", "test coverage"))
        compose.setContent { MaterialTheme { IntakeReviewCard(value.value, false) { old, text, state ->
            value.value = old.copy(revision = old.revision + 1, suggestion = old.suggestion.copy(text = text), state = state, edited = text != "Send draft")
        } } }
        compose.onNodeWithText("View source quote").performClick()
        compose.onNodeWithText("Send the draft").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Suggestion").performTextReplacement("Send the revised draft after checking its date")
        compose.onNodeWithText("Save edit").performClick()
        compose.runOnIdle { assertEquals("pending", value.value.state); assertTrue(value.value.edited) }
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.getExternalFilesDir(null), "intake-review-card.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Keep review only").performClick()
        compose.runOnIdle { assertEquals("accepted", value.value.state) }
        compose.onNodeWithText("Later").performClick()
        compose.runOnIdle { assertEquals("deferred", value.value.state) }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Discard suggestion").performClick()
        compose.runOnIdle { assertEquals("discarded", value.value.state) }
    }
}
