package dev.pocketmuse

import android.net.Uri
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class AppUpdate(val version: String, val apkUrl: Uri)

object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/Matt-Mc/Cina/releases/latest"

    /** Returns null when the published build is not newer than this installation. */
    fun latestAvailable(installedVersionCode: Int): AppUpdate? {
        val connection = URL(RELEASES_URL).openConnection() as HttpsURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Cina-Android")
        try {
            if (connection.responseCode != 200) return null
            val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val buildNumber = release.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            if (buildNumber <= installedVersionCode) return null
            val assets = release.optJSONArray("assets") ?: return null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (!asset.optString("name").endsWith(".apk")) continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/Matt-Mc/Cina/releases/download/")) continue
                return AppUpdate(release.optString("name", "Build $buildNumber"), Uri.parse(url))
            }
            return null
        } finally {
            connection.disconnect()
        }
    }
}
