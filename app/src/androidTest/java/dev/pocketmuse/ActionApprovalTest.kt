package dev.pocketmuse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ActionApprovalTest {
    @get:Rule val compose = createComposeRule()
    @Test fun connectedRecipientAndFullContentAreVisibleAndEditableBeforeApproval() {
        val body = "Important text. ".repeat(70) + "Keep the final sentence."
        val args = JSONObject().put("server", "mail").put("tool", "send_message")
            .put("arguments", JSONObject().put("recipient", "alex@example.com").put("body", body))
        var approved: ToolRequest? = null
        var futurePermission = true
        compose.setContent { MaterialTheme {
            ActionApprovalDialog(PendingAction(ToolRequest("mcp_call", args), "Send message"), null, null, { null }, {}, { request, always -> approved = request; futurePermission = always })
        } }
        compose.onNodeWithText("alex@example.com").assertExists().performTextReplacement("sam@example.com")
        compose.onNodeWithText(body).assertExists()
        compose.onNode(hasText("Run connected action") and hasClickAction()).performClick()
        assertEquals("sam@example.com", approved!!.arguments.getJSONObject("arguments").getString("recipient"))
        assertEquals(body, approved!!.arguments.getJSONObject("arguments").getString("body"))
        assertFalse(futurePermission)
    }
    @Test fun invalidActionsCannotBeApproved() {
        var approved = false
        compose.setContent { MaterialTheme {
            ActionApprovalDialog(PendingAction(ToolRequest("create_note", JSONObject().put("title", "Test").put("body", "")), "Save note"), null, null, { "body is required" }, {}, { _, _ -> approved = true })
        } }
        compose.onNode(hasText("Save note") and hasClickAction()).assertIsNotEnabled()
        assertFalse(approved)
    }
}
