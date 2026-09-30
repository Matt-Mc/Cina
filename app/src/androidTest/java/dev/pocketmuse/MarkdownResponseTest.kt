package dev.pocketmuse

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MarkdownResponseTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersMarkdownAndCopiesExactCode() {
        val source = """
            ## A simple plan

            **Start small** and stay *comfortable*.

            - Choose a route
            - Bring water

            > Leave room to change your mind.

            ```kotlin
            val minutes = 20
            ```

            | Item | Time |
            | --- | --- |
            | Walk | 10 min |
        """.trimIndent()
        compose.setContent { Column { AssistantResponse(source) } }
        listOf("A simple plan", "Start small and stay comfortable.", "Choose a route", "Bring water", "val minutes = 20", "10 min").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("val minutes = 20\n", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun updatesStreamingMarkdownAndKeepsThinkingSeparate() {
        val source = mutableStateOf("<think>Private reasoning</think>Keep **going")
        compose.setContent { Column { AssistantResponse(source.value, streaming = true) } }
        compose.onNodeWithText("Keep **going").assertIsDisplayed()
        compose.onNodeWithText("Private reasoning").assertDoesNotExist()
        compose.runOnIdle { source.value = "<think>Private reasoning</think>Keep **going**" }
        compose.onNodeWithText("Keep going").assertIsDisplayed()
        compose.onNodeWithText("View thinking").performClick()
        compose.onNodeWithText("Private reasoning").assertIsDisplayed()
    }
}
