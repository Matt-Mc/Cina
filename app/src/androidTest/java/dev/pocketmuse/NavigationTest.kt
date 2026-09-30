package dev.pocketmuse

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun drawerGroupsFeaturesIntoFiveDestinations() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        listOf("Chat", "Tasks", "Notes", "You", "Settings").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Models", "Memory", "Scheduled tasks").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
    }

    @Test fun openingModelManagementPreservesChatDraftAndUsesCompactActions() {
        val draft = "Help me plan a weekend walk"
        compose.onNodeWithContentDescription("Message Cina").performTextInput(draft)
        compose.onNodeWithText("Choose a model ⌄").performClick()
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
        val draft = "Keep this thought in this chat"
        compose.onNodeWithContentDescription("Message Cina").performTextInput(draft)
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onAllNodesWithText(draft).assertCountEquals(0)
        compose.onNodeWithText("New chat ⌄").performClick()
        compose.onAllNodesWithText("Open")[1].performClick()
        compose.onNodeWithText(draft).assertIsDisplayed()
    }

    @Test fun memoryIsAvailableFromYou() {
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("You").performClick()
        compose.onNodeWithText("About you").assertIsSelected()
        compose.onAllNodesWithText("Memory")[0].performClick()
        compose.onNodeWithText("Remember useful details").assertIsDisplayed()
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
    @get:Rule val compose = launchRule(Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_WIDGET_PAGE, "Reminders"))

    @Test fun reminderLinksOpenRemindersInsteadOfNotes() {
        compose.onNodeWithText("Reminders").assertIsSelected()
        compose.onNodeWithText("+ New reminder").assertIsDisplayed()
    }
}

class ScheduledNavigationTest {
    @get:Rule val compose = launchRule(Intent(ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java)
        .putExtra("open_scheduled_tasks", true))

    @Test fun scheduledNotificationsOpenScheduledTab() {
        compose.onNodeWithText("Scheduled").assertIsSelected()
        compose.onNodeWithText("Let Cina run a prompt later").assertIsDisplayed()
    }
}
