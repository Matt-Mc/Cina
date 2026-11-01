package dev.pocketmuse

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CollectionRepositoryTest {
    private val centre=GeoPoint(45.0,-75.0)
    private val snapshot=EssentialsSnapshot("2026-10-31T12:00:00Z",listOf(
        EssentialPlace("node/1","hospital","Far hospital",GeoPoint(45.02,-75.0),"{\"emergency\":\"yes\"}"),
        EssentialPlace("node/2","hospital","Near hospital",GeoPoint(45.001,-75.0),"{}")))
    private fun fixture(block: (CollectionRepository)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(base.cacheDir,"collections-${System.nanoTime()}.db")
        val wrapper=object:ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getDatabasePath(name: String)=file
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences(file.name,mode)
        }
        try { CollectionRepository(wrapper).use(block) }
        finally { SQLiteDatabase.deleteDatabase(file);base.getSharedPreferences(file.name,0).edit().clear().commit() }
    }
    @Test fun searchUsesDistanceAndEmergencyFilterWithoutNetwork()=fixture { repo ->
        repo.install("one","Test area",centre,5,snapshot,1200)
        assertEquals(listOf("node/2","node/1"),repo.search("hospital").hits.map { it.place.id })
        assertEquals(listOf("node/1"),repo.search("hospital",emergencyOnly=true).hits.map { it.place.id })
        val answer=repo.answer("nearest hospital")
        assertTrue(answer.contains("not your current location"));assertTrue(answer.contains("2026-10-31"))
        assertTrue(answer.contains("https://www.openstreetmap.org/node/2"))
    }
    @Test fun failedRefreshRollsBackAndDoesNotErasePreviousArea()=fixture { repo ->
        repo.install("one","Test area",centre,5,snapshot,1200)
        assertThrows(IllegalStateException::class.java) { repo.install("one","Changed",centre,5,snapshot,999) { error("Cancelled") } }
        assertEquals("Test area",repo.list().single().title)
        assertEquals(2,repo.search("hospital").hits.size)
    }
    @Test fun disablingDeletingAndMissingCoverageAreExplicit()=fixture { repo ->
        repo.install("one","Test area",centre,5,snapshot,1200)
        assertThrows(IllegalArgumentException::class.java) { repo.search("hospital","Different city") }
        assertThrows(IllegalArgumentException::class.java) { repo.answer("Find the nearest hospital in Paris") }
        repo.enable("one",false)
        assertThrows(IllegalArgumentException::class.java) { repo.search("hospital") }
        repo.delete("one");assertTrue(repo.list().isEmpty())
        assertEquals(0,repo.readableDatabase.rawQuery("SELECT count(*) FROM places",null).use { it.moveToFirst();it.getInt(0) })
    }
    @Test fun multipleAreasRequireSelectionAndOutOfCoverageLocationIsRejected()=fixture { repo ->
        repo.install("one","First",centre,5,snapshot,1200)
        repo.install("two","Second",centre,5,snapshot,1200)
        assertThrows(IllegalArgumentException::class.java) { repo.search("hospital") }
        repo.choose("one");assertEquals("one",repo.search("hospital").collection.id)
        repo.reference(GeoPoint(60.0,-75.0),"Phone location",true)
        assertThrows(IllegalArgumentException::class.java) { repo.search("hospital") }
        repo.choose("two");assertEquals("two",repo.search("hospital").collection.id)
    }
}
