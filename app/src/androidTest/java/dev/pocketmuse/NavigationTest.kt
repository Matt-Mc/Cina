package dev.pocketmuse

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.printToString
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.getExternalFilesDir(null), name).outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    @Test fun inboxManualCaptureCanBeOpenedAndDeletedWithoutAModel() {
        val marker = "Inbox UI ${java.util.UUID.randomUUID()}"
        try {
            compose.onNodeWithContentDescription("Open menu").performClick()
            compose.onNodeWithText("Library").performClick()
            compose.onNodeWithText("Inbox").performClick()
            compose.onNodeWithText("Something to keep").performTextInput(marker)
            closeSoftKeyboard()
            compose.onNodeWithText("Save to Inbox").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                LocalStore(ApplicationProvider.getApplicationContext()).use { InboxRepository(it).items().any { item -> item.title == marker } }
            }
            compose.onAllNodesWithText("Open")[0].performScrollTo().performClick()
            compose.onNodeWithText("Delete captured item").performScrollTo().performClick()
            compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) {
                LocalStore(ApplicationProvider.getApplicationContext()).use { InboxRepository(it).items().none { item -> item.title == marker } }
            }
            compose.onNodeWithText("Something to keep").assertIsDisplayed()
        } finally {
            LocalStore(ApplicationProvider.getApplicationContext()).use { store ->
                val inbox = InboxRepository(store)
                inbox.items().filter { it.title == marker }.forEach { inbox.delete(it.id) }
            }
        }
    }

    @Test fun responseModesAndLocalTimingsAreAccessible() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Responses").performClick()
        compose.onNodeWithText("Quick").performClick().assertIsSelected()
        compose.onNodeWithText("Deep").performClick().assertIsSelected()
        compose.onNodeWithText("Auto").performClick().assertIsSelected()
        compose.onNodeWithText("Response timings", substring = true).performClick()
        compose.onNodeWithText("Local response timings").assertIsDisplayed()
    }

    @Test fun drawerGroupsSavedMaterialAndWorkIntoFourDestinations() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        listOf("Chat", "Library", "Work", "Settings").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Inbox", "Notes", "You", "Models", "Memory", "Scheduled tasks").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        capture("consolidated-menu.png")
        compose.onNodeWithText("Library").performClick()
        awaitLibraryReady()
        listOf("Inbox", "Notes", "Knowledge & projects", "Memory").forEach {
            compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText(it))
            try {
                compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            } catch (failure: AssertionError) {
                throw AssertionError("Library destination '$it' is not visible. UI=${compose.onRoot().printToString()}", failure)
            }
        }
        capture("consolidated-library.png")
        compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("Manage notes"))
        compose.onNodeWithText("Manage notes").performClick()
        compose.onNodeWithContentDescription("Go back").performClick()
        awaitLibraryReady()
        compose.onNodeWithContentDescription("Saved library items").performScrollToNode(hasText("Knowledge & projects"))
        compose.onNodeWithText("Knowledge & projects").assertIsDisplayed()
    }

    private fun awaitLibraryReady() {
        compose.onNodeWithContentDescription("Saved library items").assertExists()
        lateinit var library: LibraryViewModel
        compose.runOnIdle { library = ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
        // Loading can insert rows above Browse and move a target immediately after scrolling.
        compose.waitUntil(10_000) { !library.loading.value }
        compose.waitForIdle()
    }

    @Test fun openingModelManagementPreservesChatDraftAndUsesCompactActions() {
        val draft = "Help me plan a weekend walk"
        compose.onNodeWithContentDescription("Message Cina").performTextInput(draft)
        compose.onNodeWithText("Auto ⌄").performClick()
        compose.onNodeWithText("Manage models").performClick()
        compose.onNodeWithText("Models").assertIsDisplayed()
        compose.onNodeWithContentDescription("Go back").performClick()
        compose.onNodeWithText(draft).assertIsDisplayed()
        compose.onAllNodesWithText("Start as goal").assertCountEquals(0)
        compose.onNodeWithContentDescription("Message actions").performClick()
        compose.onNodeWithText("Track as goal").assertIsDisplayed()
        compose.onNodeWithText("Attach a file").assertIsDisplayed()
    }

    @Test fun draftsStayWithTheirOwnConversation() {
        // Start from a fresh chat so earlier test runs cannot change which row contains this draft.
        compose.onNodeWithContentDescription("New chat").performClick()
        val draft = "Keep this thought in this chat"
        compose.onNodeWithContentDescription("Message Cina").performTextInput(draft)
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onAllNodesWithText(draft).assertCountEquals(0)
        compose.onNodeWithText("New chat ⌄").performClick()
        compose.onAllNodesWithText("New chat", substring = false)[0].performClick()
        compose.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test fun memoryIsAvailableFromLibraryAndProfileFromSettings() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Memory").performClick()
        compose.onNodeWithText("Every suggested detail needs your review", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("About you").performClick()
        compose.onNodeWithText("How Cina should help").assertIsDisplayed()
    }
}

private fun launchRule(intent: Intent) = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(
    ActivityScenarioRule<MainActivity>(intent)
) { rule ->
    lateinit var activity: MainActivity
    rule.scenario.onActivity { activity = it }
    activity
}

class ReminderNavigationTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = launchRule(Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_WIDGET_PAGE, "Reminders"))

    @Test fun reminderLinksOpenRemindersInsteadOfNotes() {
        compose.onNodeWithText("Reminders").assertIsSelected()
        compose.onNodeWithText("+ New reminder").assertIsDisplayed()
    }
}

class ScheduledNavigationTest {
    @get:Rule(order = 0) val permissions = NotificationPermissionRule()
    @get:Rule val compose = launchRule(Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
        .putExtra("open_scheduled_tasks", true))

    @Test fun scheduledNotificationsOpenScheduledTab() {
        compose.onNodeWithText("Scheduled").assertIsSelected()
        compose.onNodeWithText("Let Cina run a prompt later").assertIsDisplayed()
    }
}
