package dev.pocketmuse

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Host runs prepare, force-stops Cina, lets Home finish, then runs verify in a new process. */
class HomeCompanionRestartTest {
    @Test fun acceptedJobFinishesWhileThePhoneProcessIsStopped() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cinaHomeRestart")
        Assume.assumeTrue("Requires explicit two-phase host orchestration", phase in setOf("prepare", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("home_restart_fixture", 0)
        LocalStore(context).use { store ->
            val repo = HomeRepository(context, store)
            if(phase == "prepare") {
                Assume.assumeTrue("Use a disposable emulator without a real Home pairing", repo.credentials() == null)
                val encoded = requireNotNull(args.getString("cinaHomeBundle"))
                repo.pair(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
                repo.sync(); repo.pauseWorker(true)
                val chat = store.newChat()
                val id = repo.enqueue("analysis", "qwen3:4b", "Phone process restart fixture", "Analyse this passage", chat, "Analyse this passage")
                prefs.edit().putString("id", id).putLong("chat", chat).putString("server", repo.credentials()!!.serverId).commit()
                repo.sync()
                assertTrue(repo.jobs().first { it.id == id }.submitted)
                assertEquals("queued", repo.jobs().first { it.id == id }.state)
            } else {
                val id = requireNotNull(prefs.getString("id", null))
                val chat = prefs.getLong("chat", -1)
                try {
                    assertEquals(prefs.getString("server", null), repo.credentials()!!.serverId)
                    repeat(2) { repo.sync() }
                    assertEquals("completed", repo.jobs().first { it.id == id }.state)
                    assertEquals(1, store.messages(chat).count { it.body == "Verified fixture answer" })
                } finally {
                    repo.disconnect()
                    store.writableDatabase.delete("home_jobs", "id=?", arrayOf(id))
                    store.deleteChat(chat, true)
                    prefs.edit().clear().commit()
                }
            }
        }
    }
}
