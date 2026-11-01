package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Deterministic HTTP → parsing → SQLite → reopened offline search, separate from the public smoke test. */
class EssentialsTransferTest {
    @Test fun downloadedResponseSurvivesReopenWithServerGone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "essentials-transfer-test"
        val centre = GeoPoint(45.0, -75.0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                server.soTimeout = 5_000
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildList { while (true) { val line=reader.readLine(); if(line.isNullOrBlank()) break; add(line) } }
                        val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val chars = CharArray(length); var offset = 0
                        while (offset < length) { val n=reader.read(chars,offset,length-offset); check(n>0);offset+=n }
                        val body = """{"osm3s":{"timestamp_osm_base":"2026-10-31T12:00:00Z"},"elements":[{"type":"node","id":1,"lat":45.001,"lon":-75,"tags":{"amenity":"pharmacy","name":"Transfer test pharmacy"}}]}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        headers.first() + "\n" + java.net.URLDecoder.decode(chars.concatToString(), "UTF-8")
                    }
                }
                val bytes = EssentialsDownload("http://127.0.0.1:${server.localPort}", 2_000).fetch(centre, 1)
                CollectionRepository(context).use { it.install(id,"Transfer test",centre,1,Essentials.parse(bytes.toString(Charsets.UTF_8),centre,1),bytes.size.toLong()) }
                val wire=request.get(5,TimeUnit.SECONDS)
                assertTrue(wire.startsWith("POST / HTTP/1.1"))
                assertTrue(wire.contains("[bbox:"))
                assertTrue(wire.contains("out body center;"))
            }
            // No HTTP server remains; use a fresh repository connection.
            CollectionRepository(context).use { repo ->
                assertEquals("Transfer test pharmacy", repo.search("pharmacy",id).hits.single().place.name)
                assertTrue(repo.answer("nearest pharmacy",id).contains("https://www.openstreetmap.org/node/1"))
            }
        } finally { executor.shutdownNow(); CollectionRepository(context).use { it.delete(id) } }
    }
}
