package dev.pocketmuse

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

class EssentialsTransportTest {
    @Test fun stalledHeadersRespectTimeoutAndAreRetryable(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit { server.accept().use { Thread.sleep(2_000) } }
                val start = System.currentTimeMillis()
                val error = runCatching { EssentialsDownload("http://127.0.0.1:${server.localPort}", 150, 1_000).fetch(GeoPoint(45.0,-75.0),1) }.exceptionOrNull()
                assertTrue(error is SocketTimeoutException)
                assertTrue(EssentialsDownload.retryable(error as Exception))
                assertTrue(System.currentTimeMillis()-start < 1_800)
                task.cancel(true)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun cancellationClosesAStalledRequest(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val accepted = CompletableDeferred<Unit>()
                val task = executor.submit { server.accept().use { accepted.complete(Unit); Thread.sleep(5_000) } }
                val job = launch { EssentialsDownload("http://127.0.0.1:${server.localPort}", 10_000).fetch(GeoPoint(45.0,-75.0),1) }
                withTimeout(2_000) { accepted.await() }
                val start = System.currentTimeMillis()
                job.cancelAndJoin()
                assertTrue("Cancellation must not wait for the read timeout",System.currentTimeMillis()-start < 2_000)
                task.cancel(true)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun onlyTransientServiceFailuresRetry() {
        assertTrue(EssentialsDownload.retryable(MapServiceException(429)))
        assertTrue(EssentialsDownload.retryable(MapServiceException(504)))
        assertFalse(EssentialsDownload.retryable(MapServiceException(400)))
        assertFalse(EssentialsDownload.retryable(IllegalArgumentException("partial snapshot")))
    }
}
