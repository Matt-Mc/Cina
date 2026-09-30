package dev.pocketmuse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val dark = Color(0xFF26312C)
private val quiet = Color(0xFF767E78)
private val wash = Color(0xFFF0F2EC)
private val border = Color(0xFFE7E9E2)

@Composable
internal fun YouScreen(vm: AssistantViewModel, onEditProfile: () -> Unit, onOpenChat: () -> Unit) {
    val profile by vm.youProfile.collectAsState()
    val suggestions by vm.memorySuggestions.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var preferences by rememberSaveable(profile.preferences) { mutableStateOf(profile.preferences) }
    Page("You", "The details you choose to share with Cina.") {
        ScreenTabs(listOf("About you", "Memory"), tab) { tab = it }
        if (tab == 0) {
            Column(Modifier.fillMaxWidth().background(wash, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name.ifBlank { "Your profile" }, color = dark, fontFamily = FontFamily.Serif, fontSize = 25.sp)
                        if (profile.pronouns.isNotBlank()) Text(profile.pronouns, color = quiet, fontSize = 12.sp)
                    }
                    TextButton(onClick = onEditProfile) { Text("Edit") }
                }
                Text(profile.about.ifBlank { "Add a name, interests, or anything you'd like Cina to know." }, color = quiet, fontSize = 13.sp)
            }
            SectionTitle("How Cina should help")
            OutlinedTextField(preferences, { preferences = it.take(500) }, label = { Text("Response preferences") },
                placeholder = { Text("Short answers, gentle reminders, favourite topics…") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            if (preferences != profile.preferences) Button(onClick = { vm.saveYouProfile(profile.copy(preferences = preferences)) }) { Text("Save preferences") }
            NavigationRow("Memory", if (suggestions.isEmpty()) "Review what Cina remembers" else "${suggestions.size} suggested ${if (suggestions.size == 1) "memory" else "memories"} to review") { tab = 1 }
            Text("Profile details and approved memories help personalize future chats. They are stored on this phone.", color = quiet, fontSize = 12.sp)
        } else MemoryContent(vm, onOpenChat)
    }
}

@Composable
internal fun ProfileEditor(profile: YouProfile, onSave: (YouProfile) -> Unit) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var pronouns by rememberSaveable(profile.pronouns) { mutableStateOf(profile.pronouns) }
    var about by rememberSaveable(profile.about) { mutableStateOf(profile.about) }
    Page("Edit profile", "Only share what you want Cina to know.") {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pronouns, { pronouns = it.take(60) }, label = { Text("Pronouns") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(about, { about = it.take(500) }, label = { Text("About you") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(profile.copy(name = name, pronouns = pronouns, about = about)) },
            enabled = name != profile.name || pronouns != profile.pronouns || about != profile.about) { Text("Save profile") }
    }
}

@Composable
fun CompanionSettings(profile: YouProfile, onSave: (YouProfile) -> Unit) {
    var draft by remember(profile) { mutableStateOf(profile) }
    var showColourPicker by remember { mutableStateOf(false) }
    if (showColourPicker) CompanionColorPicker(draft,
        onDismiss = { showColourPicker = false },
        onApply = { draft = draft.copy(petCustomColor = it); showColourPicker = false })
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(Color(0xFFF7F2E9), RoundedCornerShape(24.dp))
            .padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionBubble(draft, 126.dp)
            Spacer(Modifier.height(6.dp))
            Text(draft.petName.ifBlank { "Your companion" }, fontFamily = FontFamily.Serif, fontSize = 23.sp, color = dark)
        }
        OutlinedTextField(draft.petName, { draft = draft.copy(petName = it.take(40)) }, label = { Text("Companion name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Colour", color = dark, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            petColors.forEachIndexed { index, color ->
                Box(Modifier.size(38.dp).clip(CircleShape).background(color)
                    .border(if(draft.petCustomColor.isEmpty() && draft.petColor == index) 3.dp else 1.dp, if(draft.petCustomColor.isEmpty() && draft.petColor == index) dark else border, CircleShape)
                    .semantics { contentDescription = "Colour ${index + 1}" }
                    .selectable(selected = draft.petCustomColor.isEmpty() && draft.petColor == index,
                        role = Role.RadioButton) { draft = draft.copy(petColor = index, petCustomColor = "") })
            }
        }
        OutlinedButton(onClick = { showColourPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.size(20.dp).background(companionColor(draft), CircleShape).border(1.dp, border, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(if (draft.petCustomColor.isEmpty()) "Create a custom colour" else "Custom colour · #${draft.petCustomColor}")
        }
        AppearanceHeading("Eyes")
        ChoiceRow(listOf("Classic", "Sparkle", "Happy", "Sleepy", "Wink", "Hearts"), draft.petEyes) { draft = draft.copy(petEyes = it) }
        AppearanceHeading("Hat")
        ChoiceRow(listOf("None", "Beanie", "Party", "Crown", "Beret", "Sprout", "Bow"), draft.petHat) { draft = draft.copy(petHat = it) }

        AppearanceHeading("Mouth")
        ChoiceRow(listOf("Smile", "Grin", "Surprised", "Calm"), draft.petMouth) { draft = draft.copy(petMouth = it) }
        AppearanceHeading("Accessories")
        ChoiceRow(listOf("None", "Glasses", "Freckles"), draft.petAccessory) { draft = draft.copy(petAccessory = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rosy cheeks", Modifier.weight(1f), color = dark)
            Switch(draft.petBlush, { draft = draft.copy(petBlush = it) },
                modifier = Modifier.semantics { contentDescription = "Rosy cheeks" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show companion in the app", Modifier.weight(1f), color = dark)
            Switch(draft.showPet, { draft = draft.copy(showPet = it) })
        }
        TextButton(onClick = { draft = draft.copy(petColor = 0, petCustomColor = "", petEyes = 0,
            petHat = 0, petMouth = 0, petAccessory = 0, petBlush = true) }) { Text("Reset appearance") }
        Button(onClick = { onSave(draft) }, enabled = draft != profile, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save customization") }
    }
}

@Composable
private fun AppearanceHeading(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = dark, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.chunked(3).forEach { indices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                indices.forEach { index ->
                    Surface(shape = RoundedCornerShape(14.dp), color = if(index == selected) dark else wash,
                        modifier = Modifier.weight(1f).selectable(selected = index == selected,
                            role = Role.RadioButton, onClick = { onSelect(index) })) {
                        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(labels[index], color = if(index == selected) Color.White else dark, fontSize = 12.sp)
                        }
                    }
                }
                repeat(3 - indices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}