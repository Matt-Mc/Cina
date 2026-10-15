package dev.pocketmuse

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal data class GoogleRequest(val url: String, val body: JSONObject?)

/** Fixed routes keep model-controlled input out of hosts, methods and authorization headers. */
internal data class GoogleOperation(
    val service: String, val name: String, val description: String, val method: String,
    val endpoint: String, val parameters: Map<String, String> = emptyMap(),
    val queryParameters: Set<String> = emptySet(), val bodyDescription: String? = null
) {
    fun schema(): JSONObject {
        val properties = JSONObject()
        parameters.forEach { (name, description) -> properties.put(name, JSONObject().put("type", "string").put("description", description)) }
        if (bodyDescription != null) properties.put("body", JSONObject().put("type", "object").put("description", bodyDescription))
        val required = parameters.keys.filter { it !in queryParameters }.toMutableList()
        if (bodyDescription != null) required += "body"
        return JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)
    }

    fun request(arguments: JSONObject): GoogleRequest {
        val allowed = parameters.keys + if (bodyDescription != null) setOf("body") else emptySet()
        require(arguments.keys().asSequence().all { it in allowed }) { "Unsupported Google tool argument." }
        var url = endpoint
        parameters.keys.filter { it !in queryParameters }.forEach { key ->
            val value = arguments.getString(key)
            require(value.isNotBlank() && value != "." && value != ".." && value.length <= 2048) { "Invalid $key." }
            url = url.replace("{$key}", encode(value))
        }
        val query = queryParameters.filter { arguments.has(it) }.joinToString("&") { key ->
            "$key=${encode(arguments.getString(key))}"
        }
        if (query.isNotEmpty()) url += (if ('?' in url) "&" else "?") + query
        return GoogleRequest(url, if (bodyDescription == null) null else arguments.getJSONObject("body"))
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

internal object GoogleService {
    private val permissions = mapOf(
        "gmail" to listOf("gmail.readonly", "gmail.compose"), "drive" to listOf("drive.metadata.readonly"),
        "docs" to listOf("documents"), "sheets" to listOf("spreadsheets"),
        "slides" to listOf("presentations"), "calendar" to listOf("calendar.events"),
        "google_chat" to listOf("chat.spaces.readonly", "chat.messages"),
        "contacts" to listOf("contacts.readonly")
    )
    fun supports(id: String) = id in permissions
    fun scopes(id: String): List<String> = listOf("openid", "https://www.googleapis.com/auth/userinfo.email") +
        permissions.getValue(id).map { "https://www.googleapis.com/auth/$it" }
    fun accessLabel(id: String): String = when (id) {
        "gmail" -> "Read email, create drafts, and send messages"
        "drive" -> "View Drive file names and details"
        "docs" -> "View and edit Google Docs"
        "sheets" -> "View and edit Google Sheets"
        "slides" -> "View and edit Google Slides"
        "calendar" -> "View and edit calendar events"
        "google_chat" -> "View spaces and read or send Chat messages"
        "contacts" -> "View your contacts"
        else -> ""
    }

    private val page = mapOf("pageToken" to "Next-page token from a previous response")
    val operations = listOf(
        GoogleOperation("gmail", "list_messages", "Search Gmail messages; use get_message for content. Supports pagination.", "GET",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=20", mapOf("q" to "Gmail search query") + page, setOf("q", "pageToken")),
        GoogleOperation("gmail", "get_message", "Read a Gmail message including headers and base64url encoded MIME parts.", "GET",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/{id}?format=full", mapOf("id" to "Message ID")),
        GoogleOperation("gmail", "create_draft", "Create a Gmail draft. Does not send email.", "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/drafts", bodyDescription = "Gmail Draft: {message: {raw: base64url-encoded RFC 2822 email including To, Subject and body}}"),
        GoogleOperation("gmail", "send_message", "Send an email to the recipients in its MIME headers. Requires action approval.", "POST",
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", bodyDescription = "Gmail Message: {raw: base64url-encoded RFC 2822 email including To, Subject and body}"),
        GoogleOperation("drive", "list_files", "Find Drive files and IDs. Supports pagination.", "GET",
            "https://www.googleapis.com/drive/v3/files?pageSize=20&fields=nextPageToken,files(id,name,mimeType,webViewLink)", mapOf("q" to "Drive API search query") + page, setOf("q", "pageToken")),
        GoogleOperation("drive", "get_file", "Read Drive file metadata. Use Docs, Sheets or Slides tools to read their contents.", "GET",
            "https://www.googleapis.com/drive/v3/files/{id}?fields=id,name,mimeType,description,webViewLink", mapOf("id" to "File ID")),
        GoogleOperation("docs", "get_document", "Read a Google document by ID.", "GET", "https://docs.googleapis.com/v1/documents/{id}", mapOf("id" to "Document ID")),
        GoogleOperation("docs", "create_document", "Create a Google document.", "POST", "https://docs.googleapis.com/v1/documents", bodyDescription = "{title: document title}"),
        GoogleOperation("docs", "update_document", "Edit a Google document using Docs batchUpdate requests.", "POST", "https://docs.googleapis.com/v1/documents/{id}:batchUpdate", mapOf("id" to "Document ID"), bodyDescription = "{requests: [Docs API Request objects], writeControl?: {requiredRevisionId: revision}}. Example: {requests:[{insertText:{endOfSegmentLocation:{},text: text}}]}"),
        GoogleOperation("sheets", "get_values", "Read spreadsheet cells in an A1 range.", "GET", "https://sheets.googleapis.com/v4/spreadsheets/{id}/values/{range}", mapOf("id" to "Spreadsheet ID", "range" to "A1 range, e.g. Sheet1!A1:D20")),
        GoogleOperation("sheets", "set_values", "Write spreadsheet cells in an A1 range as literal values.", "PUT", "https://sheets.googleapis.com/v4/spreadsheets/{id}/values/{range}?valueInputOption=RAW", mapOf("id" to "Spreadsheet ID", "range" to "A1 range"), bodyDescription = "{values: [[cell values]], majorDimension: ROWS}"),
        GoogleOperation("sheets", "create_spreadsheet", "Create a spreadsheet.", "POST", "https://sheets.googleapis.com/v4/spreadsheets", bodyDescription = "{properties: {title: spreadsheet title}}"),
        GoogleOperation("slides", "get_presentation", "Read a presentation by ID.", "GET", "https://slides.googleapis.com/v1/presentations/{id}", mapOf("id" to "Presentation ID")),
        GoogleOperation("slides", "create_presentation", "Create a presentation.", "POST", "https://slides.googleapis.com/v1/presentations", bodyDescription = "{title: presentation title}"),
        GoogleOperation("slides", "update_presentation", "Edit a presentation with Slides batchUpdate requests.", "POST", "https://slides.googleapis.com/v1/presentations/{id}:batchUpdate", mapOf("id" to "Presentation ID"), bodyDescription = "{requests: [Slides API Request objects], writeControl?: {requiredRevisionId: revision}}"),
        GoogleOperation("calendar", "list_events", "List events in a calendar. Use primary for the signed-in account. Supports pagination.", "GET", "https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events?maxResults=20&singleEvents=true&orderBy=startTime", mapOf("calendarId" to "Calendar ID, usually primary", "timeMin" to "Inclusive start RFC3339 timestamp", "timeMax" to "Exclusive end RFC3339 timestamp") + page, setOf("timeMin", "timeMax", "pageToken")),
        GoogleOperation("calendar", "create_event", "Create a calendar event. Attendees can receive invitations.", "POST", "https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events", mapOf("calendarId" to "Calendar ID, usually primary"), bodyDescription = "Calendar Event: {summary, description?, start: {dateTime, timeZone?}, end: {dateTime, timeZone?}, attendees?: [{email}]}"),
        GoogleOperation("google_chat", "list_spaces", "List Google Chat spaces available to this account.", "GET", "https://chat.googleapis.com/v1/spaces?pageSize=20", page, setOf("pageToken")),
        GoogleOperation("google_chat", "list_messages", "Read messages in a Google Chat space.", "GET", "https://chat.googleapis.com/v1/spaces/{spaceId}/messages?pageSize=20", mapOf("spaceId" to "Space ID without spaces/ prefix") + page, setOf("pageToken")),
        GoogleOperation("google_chat", "create_message", "Send a message to a Google Chat space.", "POST", "https://chat.googleapis.com/v1/spaces/{spaceId}/messages", mapOf("spaceId" to "Space ID without spaces/ prefix"), bodyDescription = "{text: message text}"),
        GoogleOperation("contacts", "list_contacts", "Read contact names, emails and phone numbers. Supports pagination.", "GET", "https://people.googleapis.com/v1/people/me/connections?personFields=names,emailAddresses,phoneNumbers&pageSize=20", page, setOf("pageToken"))
    )
    fun tools(id: String) = operations.filter { it.service == id }.map { McpTool(id, it.name, it.description, it.schema()) }
}
