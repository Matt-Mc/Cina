package dev.pocketmuse

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in connectivity diagnosis; no user content or credentials. */
class NetworkDiagnosticsTest {
    @Test fun publicHttps() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkDiagnostics") == "true")
        listOf("https://www.openstreetmap.org", "https://overpass.private.coffee/api/status", "https://overpass-api.de/api/status").forEach { url ->
            val start = System.currentTimeMillis()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Cina-Android/1.0 (https://github.com/Matt-Mc/Cina)")
            try {
                conn.connect()
                android.util.Log.i("CinaNetwork", "$url connected in ${System.currentTimeMillis()-start} ms")
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText().take(500) }
                android.util.Log.i("CinaNetwork", "$url HTTP $code: $body")
            } catch (e: Exception) { android.util.Log.i("CinaNetwork", "$url failed after ${System.currentTimeMillis()-start} ms", e) }
            finally { conn.disconnect() }
        }
    }
}
