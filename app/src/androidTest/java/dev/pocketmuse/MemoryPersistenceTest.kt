package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import android.content.ContextWrapper
import java.io.File

class MemoryPersistenceTest {
    @Test fun upgradingVersionFivePreservesExistingFacts() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "memory-upgrade-${System.nanoTime()}.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY,fact TEXT NOT NULL UNIQUE,source_chat_id INTEGER,source_message_id INTEGER)")
            db.execSQL("CREATE TABLE memory_suggestions(id INTEGER PRIMARY KEY,fact TEXT NOT NULL,source_chat_id INTEGER NOT NULL,source_message_id INTEGER NOT NULL)")
            db.execSQL("INSERT INTO memories(id,fact) VALUES(1,'User prefers tea')")
            db.version = 5
        }
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        LocalStore(context).use { store ->
            assertEquals("User prefers tea", store.activeMemories().single().fact)
            assertEquals("durable", store.memories().single().kind)
            assertNull(store.memories().single().expiresMillis)
            assertEquals(6, store.readableDatabase.version)
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
    }
    @Test fun queuedWorkAndExpirySurviveReopeningTheDatabase() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(base.cacheDir, "memory-test-${System.nanoTime()}.db")
        val context = object : ContextWrapper(base) { override fun getDatabasePath(name: String) = file }
        val store = LocalStore(context)
        val chat = store.newChat()
        val message = store.addMessage(chat, "user", "I'm visiting Lisbon next week")
        store.queueMemory(message)
        store.queueMemory(message)
        val fact = "Test trip $message"
        store.addMemory(fact, chat, message)
        store.memoryMetadata(fact, "temporary", System.currentTimeMillis() - 1, false)
        store.close()
        LocalStore(context).use { reopened ->
            assertEquals(1, reopened.pendingMemory().count { it.id == message })
            assertTrue(reopened.memories().any { it.fact == fact && it.kind == "temporary" })
            assertFalse(reopened.activeMemories().any { it.fact == fact })
            reopened.completeMemory(message)
            assertFalse(reopened.pendingMemory().any { it.id == message })
            reopened.memories().first { it.fact == fact }.let { reopened.deleteMemory(it.id) }
        }
        android.database.sqlite.SQLiteDatabase.deleteDatabase(file)
    }
}
