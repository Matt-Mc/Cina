package dev.pocketmuse

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelPickerNavigationTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modelsStartsWithThreeChoicesAndBackReturnsFromEachChoice() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Models").performScrollTo().performClick()
        listOf("Phone model", "Heavy phone model", "Home model").forEach { compose.onNodeWithContentDescription("Choose $it").assertIsDisplayed() }
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        File(base.getExternalFilesDir(null), "model-choices.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        compose.onNodeWithContentDescription("Choose Phone model").performClick()
        compose.onNodeWithText("Find a model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithContentDescription("Choose Home model").assertIsDisplayed().performClick()
        compose.onNodeWithText("Connect Home").assertIsDisplayed()
        compose.onAllNodesWithText("Search Hugging Face").assertCountEquals(0)
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithContentDescription("Choose Heavy phone model").assertIsDisplayed()
    }

    @Test fun assigningHeavyPhoneDoesNotEraseTheHomeChoice() {
        val vm = ViewModelProvider(compose.activity)[AssistantViewModel::class.java]
        val phone = vm.assignedModel(ResponseMode.QUICK)
        val heavy = vm.assignedModel(ResponseMode.DEEP)
        val home = vm.homeState.value.deepModel
        try {
            compose.runOnIdle {
                vm.assignHomeDeep("qwen3:4b")
                vm.assignModel(ResponseMode.QUICK, "/phone-fixture.gguf")
                vm.assignModel(ResponseMode.DEEP, "/heavy-fixture.gguf")
                assertEquals("qwen3:4b", vm.homeState.value.deepModel)
                assertEquals("/phone-fixture.gguf", vm.assignedModel(ResponseMode.QUICK))
                assertEquals("/heavy-fixture.gguf", vm.assignedModel(ResponseMode.DEEP))
            }
        } finally {
            compose.runOnIdle { vm.assignModel(ResponseMode.QUICK, phone); vm.assignModel(ResponseMode.DEEP, heavy); vm.assignHomeDeep(home) }
        }
    }
}
