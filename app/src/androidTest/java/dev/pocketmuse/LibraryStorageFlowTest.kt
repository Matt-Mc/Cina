package dev.pocketmuse

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import java.io.File

class LibraryStorageFlowTest {
    @get:Rule val compose=createComposeRule()
    private val base get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val database by lazy { File(base.cacheDir,"storage-flow-${System.nanoTime()}.db") }
    private val backup by lazy { File(base.cacheDir,"storage-flow-${System.nanoTime()}.cinabackup") }
    private val app by lazy { object:Application() {
        init { attachBaseContext(base) }
        override fun getDatabasePath(name: String)=database
    } }
    private lateinit var vm: LibraryStorageViewModel
    @After fun cleanup() { if(::vm.isInitialized) vm.leave();SQLiteDatabase.deleteDatabase(database);backup.delete() }
    @Test fun encryptPreviewRestoreAndCleanupThroughScreen() {
        LocalStore(app).use { store ->
            InboxRepository(store).capture("old",CapturedSource("Old reference","text/plain","Saved original".toByteArray(),"Saved original","Test"))
            store.writableDatabase.execSQL("UPDATE sources SET captured_ms=?",arrayOf(System.currentTimeMillis()-100L*86400000))
        }
        vm=LibraryStorageViewModel(app)
        compose.setContent { MaterialTheme { LibraryStorageScreen(onBack={},vm=vm) } }
        compose.waitUntil(10_000) { !vm.busy.value && vm.size.value!=null }
        compose.onNodeWithText("Prepare encrypted backup").performScrollTo().performClick()
        compose.onNodeWithText("Passphrase").performTextInput("a long test passphrase")
        compose.onNodeWithText("Repeat passphrase").performTextInput("a long test passphrase")
        compose.onNodeWithText("Encrypt backup").performClick()
        compose.waitUntil(30_000) { !vm.busy.value }
        Assert.assertTrue(vm.ready.value)
        // Exercise the file stream boundary without relying on a particular installed document provider.
        compose.runOnIdle { vm.save(Uri.fromFile(backup)) }
        compose.waitUntil(10_000) { !vm.busy.value }
        Assert.assertTrue(backup.length()>0)
        LocalStore(app).use { InboxRepository(it).delete("old") }
        compose.runOnIdle { vm.inspect(Uri.fromFile(backup),"a long test passphrase".toCharArray()) }
        compose.waitUntil(30_000) { !vm.busy.value }
        compose.onNodeWithText("Replace this library?").assertExists()
        compose.onNodeWithText("Replace library").performClick()
        compose.waitUntil(10_000) { !vm.busy.value }
        Assert.assertEquals(1L,vm.size.value!!.sources)
        compose.onNodeWithText("Preview older sources").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy.value }
        compose.onNodeWithText("Remove 1 older sources?").assertExists()
        compose.onNodeWithText("Delete these sources").performClick()
        compose.waitUntil(10_000) { !vm.busy.value }
        Assert.assertEquals(0L,vm.size.value!!.sources)
        compose.onNodeWithText("Storage & backup").performScrollTo()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(base.getExternalFilesDir(null),"storage-backup.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
