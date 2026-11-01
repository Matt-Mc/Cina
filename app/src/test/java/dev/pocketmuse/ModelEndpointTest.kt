package dev.pocketmuse

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelEndpointTest {
    @Test fun normalizesCommonServers() {
        assertEquals("http://192.168.1.20:11434/v1", ModelEndpoint.normalize(" http://192.168.1.20:11434/ "))
        assertEquals("https://example.com/inference/v1", ModelEndpoint.normalize("https://example.com/inference/v1/"))
        listOf("file:///tmp/server", "https://key@example.com", "https://example.com?key=x", "http://host:99999").forEach {
            assertTrue(runCatching { ModelEndpoint.normalize(it) }.isFailure)
        }
    }
    @Test fun sendsCompatibleChatAndParsesAnswer() {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val received = pool.submit<String> {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = buildList { while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; add(line) } }
                        val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val body = CharArray(length); var offset = 0
                        while (offset < length) { val n = reader.read(body, offset, length-offset); check(n > 0); offset += n }
                        val response = """{"choices":[{"message":{"content":"Hello from the server"}}]}"""
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
                        headers.joinToString("\n") + "\n" + body.concatToString()
                    }
                }
                val answer = ModelEndpoint().chat("http://127.0.0.1:${server.localPort}", "test-key", "test-model", listOf("user" to "Hello"))
                assertEquals("Hello from the server", answer)
                val request = received.get(5, TimeUnit.SECONDS)
                assertTrue(request.startsWith("POST /v1/chat/completions HTTP/1.1"))
                assertTrue(request.contains("Authorization: Bearer test-key"))
                assertTrue(request.contains("\"model\":\"test-model\""))
                assertTrue(request.contains("\"stream\":false"))
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun retiredProceduresAreNotExposed() {
        assertNull(ToolCatalog.get("create_skill"))
        assertNull(ToolCatalog.get("find_skill"))
        assertNotNull(ToolCatalog.get("create_scheduled_task"))
    }
}
