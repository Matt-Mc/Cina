package dev.pocketmuse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repositoryFilesExposeADownloadWithoutASearchToggle() {
        val model = RemoteModel("owner/model", "nested/model Q4.gguf", size = 400_000_000)
        var chosen: RemoteModel? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ModelFileChoices(ModelFilesState("owner/model", files = listOf(model)), ModelSlot.PHONE, emptyList(), false,
                { true }, { true }, {}, { chosen = it })
        } } }
        compose.onNodeWithText("nested/model Q4.gguf").assertIsDisplayed()
        compose.onNodeWithText("Download to phone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(model.url, chosen?.url) }
    }

    @Test fun aFailedFileRequestHasAnInlineRetryAndNoStaleDownloads() {
        var retried = false
        compose.setContent { MaterialTheme { Column {
            ModelFileChoices(ModelFilesState("owner/restricted", error = "Access required"), ModelSlot.PHONE, emptyList(), false,
                { true }, { true }, { retried = true }, { fail("Failed listing cannot download") })
        } } }
        compose.onNodeWithText("Access required").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertTrue(retried) }
        compose.onAllNodesWithText("Download to phone").assertCountEquals(0)
    }
}
