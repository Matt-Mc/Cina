package dev.pocketmuse

import okhttp3.Call
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Direct OpenAI-compatible inference. No remote jobs, tool execution, or automatic routing. */
internal class ModelEndpoint {
    @Volatile private var connection: Call? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; connection?.cancel() }

    fun models(base: String, key: String): List<String> {
        val data = request(base, key, "models", null).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }.distinct().sorted()
    }

    fun chat(base: String, key: String, model: String, messages: List<Pair<String, String>>): String {
        val body = JSONObject().put("model", model).put("stream", false)
            .put("messages", JSONArray(messages.map { (role, content) -> JSONObject().put("role", role).put("content", content) }))
        val message = request(base, key, "chat/completions", body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        require(message.optJSONArray("tool_calls")?.length().let { it == null || it == 0 }) { "This endpoint returned tool calls. Choose a chat model; endpoint chats do not run actions." }
        return message.optString("content").trim().also { require(it.isNotEmpty() && it != "null") { "The endpoint returned no answer." } }
    }

    private fun request(base: String, key: String, path: String, body: JSONObject?): JSONObject {
        check(!cancelled) { "Response stopped." }
        require(!key.contains('\r') && !key.contains('\n')) { "Invalid API key." }
        val request = Request.Builder().url("${normalize(base)}/$path").header("Accept", "application/json")
        if (key.isNotBlank()) request.header("Authorization", "Bearer $key")
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val client = NetworkClient.client.newBuilder().readTimeout(if(body == null) 20 else 180, TimeUnit.SECONDS)
            .callTimeout(if(body == null) 25 else 200, TimeUnit.SECONDS).build()
        val call = client.newCall(request.build())
        connection = call
        try {
            check(!cancelled) { "Response stopped." }
            call.execute().use { response ->
                require(response.isSuccessful) { "Endpoint returned HTTP ${response.code}. Check its address, model and API key." }
                val bytes = requireNotNull(response.body).byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        check(!cancelled) { "Response stopped." }
                        val count = input.read(buffer); if (count < 0) break
                        require(out.size() + count <= 2 * 1024 * 1024) { "Endpoint response is too large." }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                return JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } finally { connection = null }
    }

    companion object {
        fun normalize(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Enter an HTTP or HTTPS server address without a key, query or fragment."
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port." }
            val path = uri.path.orEmpty().trimEnd('/')
            return uri.toString().trimEnd('/') + if (path.endsWith("/v1")) "" else "/v1"
        }
    }
}
