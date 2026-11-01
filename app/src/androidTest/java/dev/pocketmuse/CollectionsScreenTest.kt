package dev.pocketmuse

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import java.io.File

class CollectionsScreenTest {
    @get:Rule(order=0) val permissions=NotificationPermissionRule()
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun libraryCanSearchDownloadedPlacesWithoutAModel() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id="essentials-ui-test"
        try {
            CollectionRepository(context).use { repo ->
                repo.install(id,"Fixture area",GeoPoint(45.0,-75.0),5,EssentialsSnapshot("2026-10-31T12:00:00Z",listOf(
                    EssentialPlace("node/1","pharmacy","Fixture pharmacy",GeoPoint(45.001,-75.0),"{}"))),100)
                repo.choose(id)
            }
            compose.onNodeWithContentDescription("Open menu").performClick()
            compose.onNodeWithText("Library").performClick()
            compose.onNodeWithText("Collections").performClick()
            val vm=ViewModelProvider(compose.activity)[CollectionsViewModel::class.java]
            compose.waitUntil(10_000) { vm.state.value.saved.any { it.id==id } }
            compose.onNodeWithText("Download another area").performClick()
            compose.onNodeWithText("Download Essentials area").performScrollTo().assertIsNotEnabled()
            File(context.getExternalFilesDir(null),"essentials-collections.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithTag("collections-list").performScrollToNode(hasText("Close area download"))
            compose.onNodeWithText("Close area download").performClick()
            compose.onNodeWithTag("collections-list").performScrollToNode(hasText("Find offline"))
            compose.onNodeWithText("Find offline").performClick()
            compose.waitUntil(10_000) { vm.state.value.results!=null }
            compose.onNodeWithTag("collections-list").performScrollToNode(hasText("Fixture pharmacy"))
            compose.onNodeWithText("Fixture pharmacy").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Address: not recorded\nHours in snapshot: not recorded\nWheelchair access: not recorded").performScrollTo().assertIsDisplayed()
            File(context.getExternalFilesDir(null),"essentials-results.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        } finally { CollectionRepository(context).use { it.delete(id) } }
    }
}
