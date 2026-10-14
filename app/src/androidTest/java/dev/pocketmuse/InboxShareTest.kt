package dev.pocketmuse

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class InboxShareTest {
    private val marker = "Inbox share test ${UUID.randomUUID()}"
    private val intent = Intent(ApplicationProvider.getApplicationContext<Context>(), ShareCaptureActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, marker)
    @get:Rule val compose = AndroidComposeTestRule<ActivityScenarioRule<ShareCaptureActivity>, ShareCaptureActivity>(
        ActivityScenarioRule(intent)
    ) { rule -> var activity: ShareCaptureActivity? = null; rule.scenario.onActivity { activity = it }; activity!! }

    @After fun removeOnlyTestCapture() {
        LocalStore(ApplicationProvider.getApplicationContext()).use { store ->
            val inbox = InboxRepository(store)
            inbox.items().filter { it.title == marker }.forEach { inbox.delete(it.id) }
        }
    }
    @Test fun shareWithoutModelSavesOnceAcrossActivityRecreation() {
        compose.waitUntil(10_000) {
            LocalStore(ApplicationProvider.getApplicationContext()).use { InboxRepository(it).items().count { item -> item.title == marker } == 1 }
        }
        compose.onNodeWithText("Open Inbox").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Open Inbox").assertIsDisplayed()
        LocalStore(ApplicationProvider.getApplicationContext()).use {
            assertEquals(1, InboxRepository(it).items().count { item -> item.title == marker })
        }
    }
}
