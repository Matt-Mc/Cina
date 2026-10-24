package dev.pocketmuse

import android.content.ContentValues
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class HomePersistenceTest {
    private fun fixture(block: (LocalStore, HomeRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "home-${UUID.randomUUID()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        try { LocalStore(context).use { block(it, HomeRepository(context, it)) } }
        finally { SQLiteDatabase.deleteDatabase(file) }
    }
    private fun seed(store: LocalStore, chat: Long): JSONObject {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("kind", "analysis").put("model", "qwen3:4b").put("prompt", "Test passage").put("title", "Test request")
        store.writableDatabase.insertOrThrow("home_jobs", null, ContentValues().apply {
            put("id", id); put("server", "server"); put("device", "phone"); put("chat_id", chat)
            put("body", body.toString()); put("snapshot", JSONObject(body.toString()).put("state", "queued").put("revision", 1).toString())
        })
        return body
    }
    @Test fun resultReceiptAndChatMessageCommitTogetherAndReplaysCannotDuplicate() = fixture { store, repo ->
        val chat = store.newChat()
        val result = seed(store, chat).put("state", "completed").put("output", "Home answer").put("revision", 3)
        repeat(3) { repo.save(result.getString("id"), result, true) }
        assertEquals(1, store.messages(chat).count { it.body == "Home answer" })
        assertTrue(repo.jobs().single().delivered)
        val stale = JSONObject(result.toString()).put("state", "running").put("revision", 2).put("output", "Partial")
        repo.save(result.getString("id"), stale, true)
        assertEquals("completed", repo.jobs().single().state)
        assertEquals("Home answer", repo.jobs().single().output)
    }
    @Test fun deletingChatDetachesJobsBeforeChatIdsCanBeReused() = fixture { store, repo ->
        val chat = store.newChat()
        val result = seed(store, chat).put("state", "completed").put("output", "Private old answer").put("revision", 3)
        store.deleteChat(chat, true)
        val replacement = store.newChat()
        repo.save(result.getString("id"), result, true)
        assertNull(repo.jobs().single().chat)
        assertTrue(store.messages(replacement).isEmpty())
    }
    @Test fun cancellationKeepsPartialOutputOutOfChatAndRejectsWrongIdentity() = fixture { store, repo ->
        val chat = store.newChat()
        val result = seed(store, chat).put("state", "cancelled").put("output", "Partial answer").put("revision", 3)
        assertTrue(runCatching { repo.save(result.getString("id"), JSONObject(result.toString()).put("model", "other"), true) }.isFailure)
        repo.save(result.getString("id"), result, true)
        assertTrue(store.messages(chat).isEmpty())
        assertEquals("cancelled", repo.jobs().single().state)
    }
}
