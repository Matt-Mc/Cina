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
fun YouScreen(profile: YouProfile, onSave: (YouProfile) -> Unit) {
    var draft by remember(profile) { mutableStateOf(profile) }
    val dirty = draft != profile
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("You", fontFamily = FontFamily.Serif, fontSize = 37.sp, color = dark)
        Text("The details you want Cina to know about you.", color = quiet, fontSize = 14.sp)
        Column(Modifier.fillMaxWidth().background(Color(0xFFF4F2EC), RoundedCornerShape(28.dp))
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).background(Color(0xFFD8E5D9), CircleShape), contentAlignment = Alignment.Center) {
                Text(draft.name.trim().take(1).uppercase().ifBlank { "Y" }, fontFamily = FontFamily.Serif, fontSize = 42.sp, color = dark)
            }
            Spacer(Modifier.height(12.dp))
            Text(draft.name.ifBlank { "Your name" }, fontFamily = FontFamily.Serif, fontSize = 27.sp, color = dark)
            if (draft.pronouns.isNotBlank()) Text(draft.pronouns, color = quiet, fontSize = 13.sp)
            if (draft.about.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(draft.about, color = dark, fontSize = 14.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("PRIVATE PROFILE · ON THIS DEVICE", color = quiet, fontSize = 10.sp, letterSpacing = 1.2.sp)
        }

        Text("Edit profile", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = dark)
        OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(80)) }, label = { Text("Display name") },
            placeholder = { Text("What should Cina call you?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.pronouns, { draft = draft.copy(pronouns = it.take(60)) }, label = { Text("Pronouns") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.about, { draft = draft.copy(about = it.take(500)) }, label = { Text("Bio") },
            placeholder = { Text("Your interests, goals, or anything you'd like to share…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Text("Only you and Cina can see this profile. Cina uses it to make future chats more personal.", color = quiet, fontSize = 12.sp)

        Button(onClick = { onSave(draft) }, enabled = dirty, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save profile") }
        Spacer(Modifier.height(16.dp))
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
        Text("Cina & companion", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = dark)
        Text("Choose how Cina responds and give your little companion a look of its own.", color = quiet, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().background(Color(0xFFF7F2E9), RoundedCornerShape(24.dp))
            .padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionBubble(draft, 126.dp)
            Spacer(Modifier.height(6.dp))
            Text(draft.petName.ifBlank { "Your companion" }, fontFamily = FontFamily.Serif, fontSize = 23.sp, color = dark)
        }
        OutlinedTextField(draft.preferences, { draft = draft.copy(preferences = it.take(500)) }, label = { Text("How Cina should help") },
            placeholder = { Text("Short answers, gentle reminders, favourite topics…") }, minLines = 2, modifier = Modifier.fillMaxWidth())
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
        AppearanceHeading("Eyes", draft)
        ChoiceRow(listOf("Classic", "Sparkle", "Happy", "Sleepy", "Wink", "Hearts"), draft.petEyes) { draft = draft.copy(petEyes = it) }
        AppearanceHeading("Hat", draft)
        ChoiceRow(listOf("None", "Beanie", "Party", "Crown", "Beret", "Sprout", "Bow"), draft.petHat) { draft = draft.copy(petHat = it) }

        AppearanceHeading("Mouth", draft)
        ChoiceRow(listOf("Smile", "Grin", "Surprised", "Calm"), draft.petMouth) { draft = draft.copy(petMouth = it) }
        AppearanceHeading("Accessories", draft)
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
private fun AppearanceHeading(label: String, profile: YouProfile) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = dark, fontWeight = FontWeight.Medium)
        CompanionBubble(profile, 56.dp)
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