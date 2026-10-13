package dev.pocketmuse

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in because internet availability is not a deterministic regression fixture. */
class ToolLiveSearchTest {
    @Test fun liveSearchReturnsSourceUrls() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWeb") == "true")
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "live-search-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        try {
            LocalStore(context).use { store ->
                val tools = AssistantTools(context, store)
                val chat = store.chat(store.newChat(web = true))!!
                val result = tools.execute(chat, tools.parse("web_search('stores around Ottawa that sell Riftbound')")!!)
                File(base.getExternalFilesDir(null), "live-search-result.txt").writeText(result)
                assertTrue(result, tools.succeeded(result))
                assertTrue(result, tools.resultMessage(result).contains("https://"))
            }
        } finally { android.database.sqlite.SQLiteDatabase.deleteDatabase(file) }
    }
}
