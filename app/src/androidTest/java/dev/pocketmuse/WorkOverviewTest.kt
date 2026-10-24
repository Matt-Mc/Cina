package dev.pocketmuse

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class WorkOverviewTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun overviewShowsPendingWorkAndNotificationOpensActionsWithoutApproving() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val activity=compose.activity
        val launchIntent=activity.intent
        val id=LocalStore(base).use { WorkCoordinator(it).save(WorkAction("note","Overview fixture","Private result")) }
        try {
            compose.onNodeWithContentDescription("Open menu").performClick()
            compose.onNodeWithText("Work").performClick()
            compose.onNodeWithText("Work overview").performClick()
            compose.onNodeWithText("Needs your attention").assertIsDisplayed()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Overview fixture").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Overview fixture").assertIsDisplayed()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(base.getExternalFilesDir(null),"work-overview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
            LocalStore(base).use { WorkFollowUps.recover(base,listOf(WorkCoordinator(it).task(id)!!)) }
            val manager=base.getSystemService(android.app.NotificationManager::class.java)
            val notification=manager.activeNotifications.single { it.tag=="work:$id" }.notification
            notification.contentIntent.send()
            compose.waitUntil(10000) { compose.onAllNodesWithText("New local task").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Actions").assertIsSelected()
            LocalStore(base).use { assertEquals("waiting_for_approval",WorkCoordinator(it).task(id)!!.state);assertTrue(it.notes().none { n -> n.title=="Overview fixture" }) }
        } finally {
            // ActivityScenario matches lifecycle callbacks using its launch intent. The app correctly
            // replaces that intent on notification delivery; restore it only for test-rule teardown.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent=launchIntent }
            base.getSystemService(android.app.NotificationManager::class.java).cancel("work:$id",0)
            LocalStore(base).use { store -> listOf("work_steps","durable_work_events").forEach { store.writableDatabase.delete(it,"work_id=?",arrayOf(id)) };store.writableDatabase.delete("durable_work","id=?",arrayOf(id)) }
        }
    }
}
