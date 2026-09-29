package dev.pocketmuse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class McpPreset(val id: String, val title: String, val url: String, val hint: String)
data class McpTool(val serverId: String, val name: String, val description: String, val schema: JSONObject)
data class McpConnection(val preset: McpPreset, val connected: Boolean, val toolCount: Int)

/** Remote HTTPS MCP connections. Tokens never enter prompts, messages, or action logs. */
class McpConnections(context: Context, private val secrets: SecretStore) {
    private val linearOAuth = LinearOAuth(secrets)
    companion object {
        val presets = listOf(
            McpPreset("gmail", "Gmail", "https://gmailmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("drive", "Google Drive", "https://drivemcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("docs", "Google Docs", "https://docsmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("sheets", "Google Sheets", "https://sheetsmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("slides", "Google Slides", "https://slidesmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("calendar", "Google Calendar", "https://calendarmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("google_chat", "Google Chat", "https://chatmcp.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("contacts", "Google Contacts", "https://people.googleapis.com/mcp/v1", "Google Workspace OAuth access token"),
            McpPreset("github", "GitHub", "https://api.githubcopilot.com/mcp/", "GitHub personal access token"),
            McpPreset("linear", "Linear", "https://mcp.linear.app/mcp", "Linear API key or OAuth access token")
        )
    }

    private val prefs = context.getSharedPreferences("mcp_connections", Context.MODE_PRIVATE)
    private val tools = mutableMapOf<String, List<McpTool>>()
    private val sessions = mutableMapOf<String, String>()
    private val versions = mutableMapOf<String, String>()
    private val customKey = "custom_servers"
    private fun allPresets(): List<McpPreset> {
        val saved = runCatching { JSONArray(prefs.getString(customKey, "[]")) }.getOrDefault(JSONArray())
        val custom = (0 until saved.length()).mapNotNull { i ->
            saved.optJSONObject(i)?.let { item ->
                val id = item.optString("id")
                val title = item.optString("title")
                val url = item.optString("url")
                if (id.startsWith("custom_") && title.isNotBlank() && url.isNotBlank()) McpPreset(id, title, url, "Optional bearer token") else null
            }
        }
        return presets + custom
    }

    fun snapshot(): List<McpConnection> = allPresets().map { McpConnection(it, prefs.getBoolean(it.id, false), tools[it.id]?.size ?: 0) }
    fun availableTools(): List<McpTool> = allPresets().filter { prefs.getBoolean(it.id, false) }.flatMap { tools[it.id].orEmpty() }
    fun beginLinearOAuth() = linearOAuth.begin()
    fun completeLinearOAuth(uri: android.net.Uri): Int = connect("linear", linearOAuth.complete(uri))
    fun useManualLinearToken() = linearOAuth.clear()

    fun addCustom(title: String, endpoint: String, token: String): Int {
        val name = title.trim()
        val url = endpoint.trim()
        require(name.isNotBlank() && name.length <= 80) { "Enter a server name (up to 80 characters)." }
        validateUrl(url)
        require(allPresets().none { it.url == url }) { "This server URL is already added." }
        val id = "custom_${UUID.randomUUID()}"
        val preset = McpPreset(id, name, url, "Optional bearer token")
        val credential = token.trim().also { validateToken(it) }
        val discovered = discover(preset, credential)
        require(discovered.isNotEmpty()) { "$name returned no tools." }
        val saved = JSONArray(prefs.getString(customKey, "[]"))
        saved.put(JSONObject().put("id", id).put("title", name).put("url", url))
        if (credential.isNotEmpty()) secrets.put("mcp_$id", credential)
        tools[id] = discovered
        prefs.edit().putString(customKey, saved.toString()).putBoolean(id, true).apply()
        return discovered.size
    }

    fun removeCustom(id: String) {
        require(id.startsWith("custom_") && allPresets().any { it.id == id }) { "Unknown custom server." }
        disconnect(id)
        val saved = JSONArray(prefs.getString(customKey, "[]"))
        val kept = JSONArray()
        for (i in 0 until saved.length()) saved.optJSONObject(i)?.let { if (it.optString("id") != id) kept.put(it) }
        prefs.edit().putString(customKey, kept.toString()).apply()
    }

    fun connect(id: String, newToken: String? = null): Int {
        val preset = allPresets().firstOrNull { it.id == id } ?: error("Unknown connection.")
        val token = newToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (id == "linear") linearOAuth.accessToken() ?: secrets.get("mcp_$id") else secrets.get("mcp_$id")
        require(id.startsWith("custom_") || !token.isNullOrBlank()) { "Enter an access token for ${preset.title}." }
        validateToken(token.orEmpty())
        sessions.remove(id); versions.remove(id)
        val discovered = discover(preset, token.orEmpty())
        require(discovered.isNotEmpty()) { "${preset.title} returned no tools." }
        if (newToken != null) secrets.put("mcp_$id", token.orEmpty())
        tools[id] = discovered
        prefs.edit().putBoolean(id, true).apply()
        return discovered.size
    }

    fun disconnect(id: String) {
        require(allPresets().any { it.id == id })
        tools.remove(id); sessions.remove(id); versions.remove(id)
        secrets.put("mcp_$id", "")
        if (id == "linear") linearOAuth.clear()
        prefs.edit().putBoolean(id, false).apply()
    }

    fun refreshEnabled() {
        allPresets().filter { prefs.getBoolean(it.id, false) }.forEach { preset ->
            try { connect(preset.id) } catch (_: Exception) { tools.remove(preset.id) }
        }
    }

    fun call(id: String, name: String, arguments: JSONObject): String {
        val preset = allPresets().firstOrNull { it.id == id } ?: error("Unknown connection.")
        require(prefs.getBoolean(id, false)) { "${preset.title} is disconnected." }
        require(tools[id]?.any { it.name == name } == true) { "Tool is unavailable. Reconnect ${preset.title}." }
        val token = if (id == "linear") linearOAuth.accessToken() ?: secrets.get("mcp_$id")
            else secrets.get("mcp_$id")
        require(id.startsWith("custom_") || !token.isNullOrBlank()) { "Reconnect ${preset.title}." }
        val result = rpc(preset, token.orEmpty(), "tools/call", JSONObject().put("name", name).put("arguments", arguments))
        val parts = result.optJSONArray("content") ?: JSONArray()
        val output = (0 until parts.length()).mapNotNull { index ->
            val part = parts.optJSONObject(index) ?: return@mapNotNull null
            when (part.optString("type")) {
                "text" -> part.optString("text")
                "resource_link" -> part.optString("uri")
                else -> null
            }
        }.joinToString("\n").take(12_000)
        if (result.optBoolean("isError")) error(output.ifBlank { "Remote tool failed." })
        return output.ifBlank { result.optJSONObject("structuredContent")?.toString()?.take(12_000) ?: "Tool completed." }
    }

    private fun discover(preset: McpPreset, token: String): List<McpTool> {
        val found = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject().also { if (cursor != null) it.put("cursor", cursor) }
            val result = rpc(preset, token, "tools/list", params)
            val array = result.optJSONArray("tools") ?: error("Invalid tools/list response.")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.getString("name")
                if (name.length <= 120) found += McpTool(preset.id, name, item.optString("description").take(240), item.optJSONObject("inputSchema") ?: JSONObject())
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null && found.size < 100)
        return found.take(100)
    }

    private fun rpc(preset: McpPreset, token: String, method: String, params: JSONObject): JSONObject {
        val version = versions[preset.id] ?: "2026-07-28"
        return try { request(preset, token, method, params, version) }
        catch (e: UnsupportedVersion) {
            if (version != "2026-07-28") throw e
            initializeLegacy(preset, token)
            request(preset, token, method, params, "2025-03-26")
        }
    }

    private fun initializeLegacy(preset: McpPreset, token: String) {
        val params = JSONObject().put("protocolVersion", "2025-03-26")
            .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "Cina").put("version", "0.1"))
        val result = request(preset, token, "initialize", params, "2025-03-26")
        val negotiated = result.optString("protocolVersion", "2025-03-26")
        require(negotiated in setOf("2025-03-26", "2025-06-18", "2025-11-25")) { "Unsupported MCP protocol version: $negotiated" }
        versions[preset.id] = negotiated
        request(preset, token, "notifications/initialized", JSONObject(), negotiated, notification = true)
    }

    private fun validateUrl(value: String) {
        val url = URL(value)
        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.ref == null) { "Enter an HTTPS MCP URL without credentials or a fragment." }
        require(value.length <= 2048) { "MCP URL is too long." }
    }

    private fun validateToken(value: String) {
        require(value.length <= 4096 && !value.contains('\n') && !value.contains('\r')) { "Invalid access token." }
    }

    private fun request(preset: McpPreset, token: String, method: String, params: JSONObject, version: String, notification: Boolean = false): JSONObject {
        val url = URL(preset.url)
        validateUrl(preset.url)
        val bodyParams = JSONObject(params.toString())
        if (version == "2026-07-28") bodyParams.put("_meta", JSONObject()
            .put("io.modelcontextprotocol/protocolVersion", version)
            .put("io.modelcontextprotocol/clientCapabilities", JSONObject())
            .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "Cina").put("version", "0.1")))
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", bodyParams)
        if (!notification) body.put("id", 1)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; instanceFollowRedirects = false; doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("MCP-Protocol-Version", version)
            if (version == "2026-07-28") {
                setRequestProperty("Mcp-Method", method)
                if (method == "tools/call") setRequestProperty("Mcp-Name", params.getString("name"))
            }
            sessions[preset.id]?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401 || status == 403) error("${preset.title} rejected the token. Check its permissions or renew it.")
            if (status == 400 || status == 404 || status == 406 || status == 415) {
                if (version == "2026-07-28") throw UnsupportedVersion()
            }
            require(status in 200..299) { "${preset.title} returned HTTP $status." }
            connection.getHeaderField("Mcp-Session-Id")?.let { sessions[preset.id] = it }
            if (notification || status == 202 || status == 204) return JSONObject()
            val response = connection.inputStream.bufferedReader().use { it.readText().take(1_000_000) }
            val json = if (connection.contentType?.startsWith("text/event-stream") == true) {
                response.lineSequence().filter { it.startsWith("data:") }
                    .mapNotNull { line -> runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() }
                    .firstOrNull { it.optInt("id", -1) == 1 }
                    ?: error("MCP server returned no matching response event.")
            } else JSONObject(response)
            val errorCode = json.optJSONObject("error")?.optInt("code")
            if (version == "2026-07-28" && method == "tools/list" && (errorCode == -32601 || errorCode == -32602)) throw UnsupportedVersion()
            json.optJSONObject("error")?.let { error("${preset.title}: ${it.optString("message", "MCP error").take(300)}") }
            return json.optJSONObject("result") ?: error("Invalid MCP response.")
        } finally { connection.disconnect() }
    }

    private class UnsupportedVersion : RuntimeException()
}
