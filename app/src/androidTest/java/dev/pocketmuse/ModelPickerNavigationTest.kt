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

    @Test fun modelsShowsPhoneChoicesAndBackReturns() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Phone model").performScrollTo().performClick()
        compose.onNodeWithText("Heavy phone model").assertDoesNotExist()
        compose.onNodeWithText("Downloaded").assertExists()
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        File(base.getExternalFilesDir(null), "model-choices.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
    }
}
