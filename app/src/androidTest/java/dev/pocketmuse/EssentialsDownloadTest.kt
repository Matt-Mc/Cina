package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in smoke test of the real public endpoint; never part of the ordinary offline suite. */
class EssentialsDownloadTest {
    @Test fun downloadAnAreaThenReadItLocally() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEssentials")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val work=WorkManager.getInstance(context)
        val id="essentials-live-test"
        try {
            CollectionDownloadWorker.enqueue(context,id,"Ottawa smoke test",GeoPoint(45.4215,-75.6972),1)
            val deadline=System.currentTimeMillis()+260_000
            var done: WorkInfo?=null
            while(System.currentTimeMillis()<deadline) {
                val info=work.getWorkInfosForUniqueWork(CollectionDownloadWorker.TAG).get().lastOrNull()
                if(info?.state?.isFinished==true) { done=info;break }
                Thread.sleep(250)
            }
            assertNotNull("Download did not complete within the bounded retry window",done)
            assertEquals(done!!.outputData.getString("message"),WorkInfo.State.SUCCEEDED,done.state)
            CollectionRepository(context).use { repo ->
                val saved=repo.list().first { it.id==id }
                assertTrue(saved.count>0)
                assertTrue(saved.bytes>0)
                val hits=repo.search("pharmacy",id).hits
                assertTrue("Downloaded area must contain pharmacies",hits.isNotEmpty())
                assertTrue(hits.all { it.place.source.startsWith("https://www.openstreetmap.org/") })
            }
        } finally {
            work.cancelUniqueWork(CollectionDownloadWorker.TAG).result.get()
            if(InstrumentationRegistry.getArguments().getString("keepEssentials")!="true") CollectionRepository(context).use { it.delete(id) }
        }
    }
    @Test fun downloadedAreaSurvivesRestartAndWorksOffline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyOfflineEssentials")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            CollectionRepository(context).use { repo ->
                val id="essentials-live-test"
                val saved=repo.list().single { it.id==id }
                assertTrue(saved.count>0)
                val hits=repo.search("pharmacy",id).hits
                assertTrue(hits.isNotEmpty())
                assertEquals(hits.map { it.metres }.sorted(),hits.map { it.metres })
                val answer=repo.answer("nearest pharmacy",id)
                assertTrue(answer.contains(saved.snapshot))
                assertTrue(answer.contains(hits.first().place.source))
            }
        } finally { CollectionRepository(context).use { it.delete("essentials-live-test") } }
    }
}
