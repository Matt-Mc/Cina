package dev.pocketmuse

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Browser based authorization for Linear's hosted MCP server. */
internal class LinearOAuth(private val secrets: SecretStore) {
    companion object {
        const val REDIRECT = "dev.pocketmuse.cina://oauth"
        private const val ORIGIN = "https://mcp.linear.app"
        private const val RESOURCE = "$ORIGIN/mcp"
    }

    fun begin(): Uri {
        val metadata = getJson("$ORIGIN/.well-known/oauth-authorization-server")
        require(metadata.getString("issuer") == ORIGIN) { "Unexpected Linear authorization server." }
        val register = trustedUrl(metadata.getString("registration_endpoint"))
        val authorize = trustedUrl(metadata.getString("authorization_endpoint"))
        val client = postJson(register, JSONObject().put("client_name", "Cina")
            .put("redirect_uris", org.json.JSONArray().put(REDIRECT))
            .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", org.json.JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none"))
        val clientId = client.getString("client_id")
        secrets.put("linear_oauth_client", clientId)
        secrets.put("linear_oauth_secret", client.optString("client_secret"))
        secrets.put("linear_oauth_token_endpoint", trustedUrl(metadata.getString("token_endpoint")))
        val verifier = randomUrlSafe(32)
        val state = randomUrlSafe(24)
        secrets.put("linear_oauth_verifier", verifier)
        secrets.put("linear_oauth_state", state)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Uri.parse(authorize).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("resource", RESOURCE)
            .appendQueryParameter("scope", "read write")
            .build()
    }

    fun complete(uri: Uri): String {
        require(uri.scheme == "dev.pocketmuse.cina" && uri.host == "oauth") { "Invalid sign-in callback." }
        val expected = secrets.get("linear_oauth_state") ?: error("Start Linear sign-in again.")
        require(uri.getQueryParameter("state") == expected) { "Linear sign-in could not be verified." }
        require(uri.getQueryParameter("iss")?.let { it == ORIGIN } != false) { "Unexpected Linear sign-in issuer." }
        uri.getQueryParameter("error")?.let { error("Linear sign-in was cancelled: $it") }
        val code = uri.getQueryParameter("code") ?: error("Linear did not return a sign-in code.")
        val verifier = secrets.get("linear_oauth_verifier") ?: error("Start Linear sign-in again.")
        secrets.put("linear_oauth_state", "")
        secrets.put("linear_oauth_verifier", "")
        val token = postForm(tokenEndpoint(), mapOf("grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to REDIRECT, "client_id" to clientId(), "code_verifier" to verifier, "resource" to RESOURCE))
        saveToken(token)
        return token.getString("access_token")
    }

    fun accessToken(): String? {
        val access = secrets.get("linear_oauth_access") ?: return null
        val expiry = secrets.get("linear_oauth_expiry")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() + 60_000 < expiry) return access
        val refresh = secrets.get("linear_oauth_refresh") ?: error("Linear sign-in expired. Sign in again.")
        val token = postForm(tokenEndpoint(), mapOf("grant_type" to "refresh_token", "refresh_token" to refresh,
            "client_id" to clientId(), "resource" to RESOURCE))
        saveToken(token)
        return token.getString("access_token")
    }

    fun clear() {
        listOf("client", "secret", "token_endpoint", "verifier", "state", "access", "refresh", "expiry")
            .forEach { secrets.put("linear_oauth_$it", "") }
    }

    private fun saveToken(token: JSONObject) {
        secrets.put("linear_oauth_access", token.getString("access_token"))
        token.optString("refresh_token").takeIf { it.isNotBlank() }?.let { secrets.put("linear_oauth_refresh", it) }
        val seconds = token.optLong("expires_in", 3600).coerceAtLeast(60)
        secrets.put("linear_oauth_expiry", (System.currentTimeMillis() + seconds * 1000).toString())
    }

    private fun clientId() = secrets.get("linear_oauth_client") ?: error("Start Linear sign-in again.")
    private fun tokenEndpoint() = trustedUrl(secrets.get("linear_oauth_token_endpoint") ?: error("Start Linear sign-in again."))
    private fun trustedUrl(value: String): String {
        val url = URL(value)
        require(url.protocol == "https" && url.host == "mcp.linear.app") { "Untrusted Linear authorization endpoint." }
        return value
    }
    private fun randomUrlSafe(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }.let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
    private fun getJson(url: String): JSONObject = request(url, "GET", null, null)
    private fun postJson(url: String, body: JSONObject): JSONObject = request(url, "POST", "application/json", body.toString())
    private fun postForm(url: String, fields: Map<String, String>): JSONObject {
        val values = fields.toMutableMap()
        secrets.get("linear_oauth_secret")?.let { values["client_secret"] = it }
        return request(url, "POST", "application/x-www-form-urlencoded", values.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" })
    }
    private fun request(url: String, method: String, contentType: String?, body: String?): JSONObject {
        val connection = (URL(trustedUrl(url)).openConnection() as HttpURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType) }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if(connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText().take(64_000) }.orEmpty()
            require(connection.responseCode in 200..299) { "Linear sign-in failed (HTTP ${connection.responseCode}). ${response.take(180)}" }
            return JSONObject(response)
        } finally { connection.disconnect() }
    }
}
