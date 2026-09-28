package dev.pocketmuse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class McpPreset(val id: String, val title: String, val url: String, val hint: String)
data class McpTool(val serverId: String, val name: String, val description: String, val schema: JSONObject)
data class McpConnection(val preset: McpPreset, val connected: Boolean, val toolCount: Int)

/** Remote HTTPS MCP connections. Tokens never enter prompts, messages, or action logs. */
class McpConnections(context: Context, private val secrets: SecretStore) {
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

    fun snapshot(): List<McpConnection> = presets.map { McpConnection(it, prefs.getBoolean(it.id, false), tools[it.id]?.size ?: 0) }
    fun availableTools(): List<McpTool> = presets.filter { prefs.getBoolean(it.id, false) }.flatMap { tools[it.id].orEmpty() }

    fun connect(id: String, newToken: String? = null): Int {
        val preset = presets.firstOrNull { it.id == id } ?: error("Unknown connection.")
        val token = newToken?.trim()?.takeIf { it.isNotEmpty() } ?: secrets.get("mcp_$id")
        require(!token.isNullOrBlank()) { "Enter an access token for ${preset.title}." }
        require(token.length <= 4096 && !token.contains('\n') && !token.contains('\r')) { "Invalid access token." }
        sessions.remove(id); versions.remove(id)
        val discovered = discover(preset, token)
        require(discovered.isNotEmpty()) { "${preset.title} returned no tools." }
        if (newToken != null) secrets.put("mcp_$id", token)
        tools[id] = discovered
        prefs.edit().putBoolean(id, true).apply()
        return discovered.size
    }

    fun disconnect(id: String) {
        require(presets.any { it.id == id })
        tools.remove(id); sessions.remove(id); versions.remove(id)
        secrets.put("mcp_$id", "")
        prefs.edit().putBoolean(id, false).apply()
    }

    fun refreshEnabled() {
        presets.filter { prefs.getBoolean(it.id, false) }.forEach { preset ->
            try { connect(preset.id) } catch (_: Exception) { tools.remove(preset.id) }
        }
    }

    fun call(id: String, name: String, arguments: JSONObject): String {
        val preset = presets.firstOrNull { it.id == id } ?: error("Unknown connection.")
        require(prefs.getBoolean(id, false)) { "${preset.title} is disconnected." }
        require(tools[id]?.any { it.name == name } == true) { "Tool is unavailable. Reconnect ${preset.title}." }
        val token = secrets.get("mcp_$id") ?: error("Reconnect ${preset.title}.")
        val result = rpc(preset, token, "tools/call", JSONObject().put("name", name).put("arguments", arguments))
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
        request(preset, token, "initialize", params, "2025-03-26")
        versions[preset.id] = "2025-03-26"
        request(preset, token, "notifications/initialized", JSONObject(), "2025-03-26", notification = true)
    }

    private fun request(preset: McpPreset, token: String, method: String, params: JSONObject, version: String, notification: Boolean = false): JSONObject {
        val url = URL(preset.url)
        require(url.protocol == "https") { "MCP connections require HTTPS." }
        val bodyParams = JSONObject(params.toString())
        if (version == "2026-07-28") bodyParams.put("_meta", JSONObject().put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "Cina").put("version", "0.1")))
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", bodyParams)
        if (!notification) body.put("id", 1)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; instanceFollowRedirects = false; doOutput = true
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("Authorization", "Bearer $token")
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
                val data = response.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.firstOrNull { it.startsWith("{") }
                    ?: error("MCP server returned no response event.")
                JSONObject(data)
            } else JSONObject(response)
            json.optJSONObject("error")?.let { error("${preset.title}: ${it.optString("message", "MCP error").take(300)}") }
            return json.optJSONObject("result") ?: error("Invalid MCP response.")
        } finally { connection.disconnect() }
    }

    private class UnsupportedVersion : RuntimeException()
}
