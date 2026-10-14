package dev.pocketmuse

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Device authorization is owned by Google Play services; no refresh token or client secret is stored. */
internal class GoogleConnections(context: Context, private val secrets: SecretStore) {
    val client = Identity.getAuthorizationClient(context.applicationContext)
    fun account(id: String): String? = secrets.get("google_account_$id")

    fun request(id: String): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(
            GoogleService.scopes(id).map(::Scope))
        account(id)?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }

    // Call only on a worker thread. Reauthorization silently obtains fresh tokens until consent is revoked.
    fun token(id: String): String {
        check(account(id) != null) { "Connect with Google again." }
        val result = Tasks.await(client.authorize(request(id)), 60, TimeUnit.SECONDS)
        check(!result.hasResolution()) { "Google needs your permission. Reconnect this service in Settings." }
        return checkedToken(id, result)
    }

    fun complete(id: String, result: AuthorizationResult) {
        val token = checkedToken(id, result)
        val profile = JSONObject(http("https://www.googleapis.com/oauth2/v3/userinfo", token))
        val email = profile.optString("email")
        check(email.isNotBlank() && profile.optBoolean("email_verified")) { "Google did not return a verified account. Try connecting again." }
        secrets.put("google_account_$id", email)
    }

    private fun checkedToken(id: String, result: AuthorizationResult): String {
        check(result.grantedScopes.containsAll(GoogleService.scopes(id))) { "Required permissions were not granted. Connect again and approve the listed access." }
        return result.accessToken?.takeIf { it.isNotBlank() } ?: error("Google sign-in did not return access. Try again.")
    }

    fun disconnect(id: String) { secrets.put("google_account_$id", "") }

    fun call(id: String, name: String, arguments: JSONObject): String {
        val operation = GoogleService.operations.singleOrNull { it.service == id && it.name == name }
            ?: error("Unknown Google tool.")
        val request = operation.request(arguments)
        val token = token(id)
        return try { http(request.url, token, operation.method, request.body) }
        catch (e: GoogleHttpException) {
            if (e.status == 401) {
                // Do not replay mutations after a network response. The next user-approved call gets a new token.
                Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
                error("Google access expired. Try the request again; Cina will obtain fresh access.")
            }
            throw e
        }
    }

    companion object {
        internal fun http(url: String, token: String, method: String = "GET", body: JSONObject? = null): String {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
            }
            try {
                body?.let { connection.outputStream.use { out -> out.write(it.toString().toByteArray(Charsets.UTF_8)) } }
                val status = connection.responseCode
                if (status !in 200..299) throw GoogleHttpException(status)
                return connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(12_001)
                    var length = 0
                    while (length < buffer.size) {
                        val read = reader.read(buffer, length, buffer.size - length)
                        if (read < 0) break
                        length += read
                    }
                    String(buffer, 0, minOf(length, 12_000)) + if (length > 12_000) "\n[Response truncated; narrow the request or request the next page.]" else ""
                }
            } finally { connection.disconnect() }
        }
    }
}

internal class GoogleHttpException(val status: Int) : IllegalStateException(when (status) {
    401 -> "Google access expired. Reconnect this service."
    403 -> "Google denied access. Check granted permissions, your organization's policies, and Cina's API configuration."
    404 -> "Google could not find that item, or this account cannot access it."
    429 -> "Google is receiving too many requests. Try again shortly."
    else -> "Google returned HTTP $status. Check the service and request before trying again."
})
