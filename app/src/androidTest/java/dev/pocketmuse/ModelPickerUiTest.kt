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

    @Test fun overviewHasOnlyTheThreeModelChoices() {
        var opened: ModelSlot? = null
        compose.setContent { MaterialTheme { Column { ModelSlotOverview("Small phone", "Bigger phone", "qwen3:4b", true) { opened = it } } } }
        listOf("Phone model", "Heavy phone model", "Home model").forEach { compose.onNodeWithContentDescription("Choose $it").assertIsDisplayed() }
        listOf("Installed", "Discover", "Files").forEach { compose.onAllNodesWithText(it).assertCountEquals(0) }
        compose.onNodeWithContentDescription("Choose Home model").performClick()
        compose.runOnIdle { assertEquals(ModelSlot.HOME, opened) }
    }

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

    @Test fun homeDownloadsUseServerModelNamesAndNeverPhoneFileActions() {
        var chosen: String? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            HomeModelsPane(HomeState(paired = true, online = true, catalog = listOf("qwen3:4b")), emptyList(), {},
                { chosen = it }, { _, _ -> }, {}, {})
        } } }
        compose.onNodeWithContentDescription("Download qwen3:4b on Home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("qwen3:4b", chosen) }
        compose.onAllNodesWithText("Search Hugging Face").assertCountEquals(0)
        compose.onAllNodesWithText("Download to phone").assertCountEquals(0)
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
    @Test fun hubFileCanDownloadOnHomeWhileThePhoneHasAnotherDownload() {
        val model = RemoteModel("Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q8_0.gguf", size = 1_834_426_016)
        var chosen: String? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ModelFileChoices(ModelFilesState(model.repo, files = listOf(model)), ModelSlot.HOME, emptyList(), true,
                { true }, { ModelChoices.homeName(it) != null }, {}, { chosen = ModelChoices.homeName(it) })
        } } }
        compose.onNodeWithText("Download on Home").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("hf.co/Qwen/Qwen3-1.7B-GGUF:Qwen3-1.7B-Q8_0.gguf", chosen) }
        compose.onAllNodesWithText("Download to phone").assertCountEquals(0)
    }
    @Test fun queuedHomeHubFilesCannotStartAnotherDownload() {
        val model = RemoteModel("owner/repo", "model.gguf", size = 4_000_000_000)
        compose.setContent { MaterialTheme { Column {
            ModelFileChoices(ModelFilesState(model.repo, files = listOf(model)), ModelSlot.HOME, emptyList(), false,
                { true }, { true }, {}, { fail("Duplicate Home download") }, homePending = setOf(ModelChoices.homeName(model)!!))
        } } }
        compose.onNodeWithText("Queued on Home").assertIsNotEnabled()
    }
}
