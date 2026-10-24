package dev.pocketmuse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
internal fun LibraryScreen(vm: AssistantViewModel, onNavigate: (String) -> Unit) {
    val suggestions by vm.memorySuggestions.collectAsState()
    Page("Library", "Keep useful material together and choose what Cina remembers.") {
        if (BuildConfig.INBOX_ENABLED) {
            NavigationRow("Inbox", "Capture and review shared text, links and documents") { onNavigate("Inbox") }
        }
        NavigationRow("Notes", "Write, find and edit things worth keeping") { onNavigate("Notes") }
        if (BuildConfig.INBOX_ENABLED) {
            NavigationRow("Knowledge & projects", "Find sources and review current project facts") { onNavigate("Knowledge") }
        }
        NavigationRow("Memory", if (suggestions.isEmpty()) "Review personal details used in conversations" else "${suggestions.size} suggested details to review") { onNavigate("Memory") }
        if (BuildConfig.INBOX_ENABLED) {
            NavigationRow("Storage & backup", "Manage captured sources and encrypted library backups") { onNavigate("Library storage") }
        }
    }
}
