package dev.pocketmuse

import org.json.JSONObject
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal data class HomeCredentials(val url: String, val fingerprint: String, val serverId: String, val token: String, val deviceId: String) {
    fun json() = JSONObject().put("url", url).put("fingerprint", fingerprint).put("server_id", serverId).put("token", token).put("device_id", deviceId)
    companion object {
        fun read(j: JSONObject) = HomeCredentials(HomeClient.validUrl(j.getString("url")), HomeClient.validPin(j.getString("fingerprint")), j.getString("server_id"), j.getString("token"), j.getString("device_id"))
    }
}

/** A pinned, self-signed server certificate is the identity. No global TLS overrides. */
internal class HomeClient(private val url: String, fingerprint: String, private val token: String = "") {
    private val pin = validPin(fingerprint)
    private fun matches(cert: X509Certificate): Boolean = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it) } == pin
    private val ssl = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Client certificate not supported")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !matches(chain[0])) throw CertificateException("Home certificate changed. Verify and pair again on your server.")
                chain[0].checkValidity()
            }
        }), null)
    }
    init { validUrl(url) }
    fun call(path: String, body: JSONObject? = null): JSONObject {
        require(path.startsWith("/v1/"))
        val connection = URI(url + path).toURL().openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        connection.sslSocketFactory = ssl.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
            host == URI(url).host && runCatching { matches(session.peerCertificates[0] as X509Certificate) }.getOrDefault(false)
        }
        connection.connectTimeout = 10_000; connection.readTimeout = 55_000
        connection.instanceFollowRedirects = false
        if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(8 * 1024 * 1024) } ?: byteArrayOf()
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299) error(json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Home returned an error ($status).")
            return json ?: error("Home returned an invalid response.")
        } finally { connection.disconnect() }
    }
    companion object {
        fun validUrl(raw: String): String {
            val uri = URI(raw.trim().removeSuffix("/"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty() && uri.port in -1..65535 && uri.port != 0) { "Use an HTTPS server address with no path, password or query." }
            return uri.toString()
        }
        fun validPin(raw: String): String = raw.replace(":", "").lowercase().also {
            require(it.matches(Regex("[a-f0-9]{64}"))) { "The pairing certificate fingerprint must contain 64 hexadecimal characters." }
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit) { "Home response is too large." }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
