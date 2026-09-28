package dev.pocketmuse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val petColors = listOf(
    Color(0xFFFFEAB6), Color(0xFFBFE1CE), Color(0xFFFFC9C0),
    Color(0xFFC9D8F3), Color(0xFFDCCBEC), Color(0xFFF4D5B2)
)
private val dark = Color(0xFF26312C)
private val quiet = Color(0xFF767E78)
private val wash = Color(0xFFF0F2EC)
private val border = Color(0xFFE7E9E2)

@Composable
fun CompanionBubble(profile: YouProfile, diameter: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "Companion motion")
    val bob by motion.animateFloat(0f, -3f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "Float")
    val blink by motion.animateFloat(1f, 1f, infiniteRepeatable(
        keyframes { durationMillis = 4300; 1f at 0; 1f at 3700; .08f at 3790; 1f at 3950; 1f at 4300 }
    ), label = "Blink")
    val ink = Color(0xFF28312D)
    val eyeInk = Color.Black
    Canvas(modifier.size(diameter).graphicsLayer { translationY = bob * density }) {
        val d = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val base = petColors[profile.petColor.coerceIn(0, 5)]
        val light = lerp(base, Color.White, .58f)
        val shade = lerp(base, Color(0xFF33445A), .38f)
        drawOval(Color.Black.copy(alpha = .13f), Offset(d * .12f, d * .82f), Size(d * .76f, d * .12f))
        drawCircle(Color.Black.copy(alpha = .11f), d * .43f, center.copy(y = center.y + d * .035f))
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0f to light, .43f to base, .78f to lerp(base, shade, .45f), 1f to shade),
                center = Offset(d * .30f, d * .24f), radius = d * .78f
            ), radius = d * .43f, center = center
        )
        drawCircle(ink.copy(alpha = .8f), d * .43f, center, style = Stroke(width = d * .023f))
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = .78f), Color.White.copy(alpha = .23f), Color.Transparent),
                center = Offset(d * .31f, d * .27f), radius = d * .27f
            ), topLeft = Offset(d * .17f, d * .12f), size = Size(d * .43f, d * .34f)
        )
        drawOval(Color.White.copy(alpha = .28f), Offset(d * .24f, d * .17f), Size(d * .24f, d * .09f))
        val eyeY = d * .49f
        val leftX = d * .37f
        val rightX = d * .63f
        when (profile.petEyes) {
            1 -> {
                drawCircle(eyeInk, d * .055f, Offset(leftX, eyeY))
                drawCircle(eyeInk, d * .055f, Offset(rightX, eyeY))
                drawCircle(Color.White, d * .016f, Offset(leftX - d * .014f, eyeY - d * .018f))
                drawCircle(Color.White, d * .016f, Offset(rightX - d * .014f, eyeY - d * .018f))
            }
            2 -> {
                listOf(leftX, rightX).forEach { x ->
                    val arc = Path().apply {
                        moveTo(x - d * .062f, eyeY)
                        quadraticTo(x, eyeY - d * .092f, x + d * .062f, eyeY)
                    }
                    drawPath(arc, eyeInk, style = Stroke(width = d * .035f))
                }
            }
            else -> {
                listOf(leftX, rightX).forEach { x ->
                    drawOval(eyeInk, Offset(x - d * .057f, eyeY - d * .095f * blink), Size(d * .114f, d * .19f * blink))
                }
            }
        }
        when (profile.petHat) {
            1 -> { // beanie
                val hat = Path().apply {
                    moveTo(d * .25f, d * .20f)
                    quadraticTo(d * .50f, -d * .08f, d * .75f, d * .20f)
                    lineTo(d * .75f, d * .27f)
                    lineTo(d * .25f, d * .27f)
                    close()
                }
                drawPath(hat, Color(0xFF8E756B))
                drawPath(hat, ink, style = Stroke(width = d * .025f))
                drawLine(Color(0xFFF9E9D9), Offset(d * .25f, d * .24f), Offset(d * .75f, d * .24f), d * .05f)
                drawCircle(Color(0xFF8E756B), d * .055f, Offset(d * .5f, d * .055f))
            }
            2 -> { // party hat
                val hat = Path().apply { moveTo(d * .50f, d * .015f); lineTo(d * .69f, d * .23f); lineTo(d * .30f, d * .23f); close() }
                drawPath(hat, Color(0xFFDE806C))
                drawPath(hat, ink, style = Stroke(width = d * .025f))
                drawCircle(Color(0xFFFFEAB6), d * .045f, Offset(d * .5f, d * .01f))
                drawCircle(Color.White, d * .025f, Offset(d * .48f, d * .13f))
            }
            3 -> { // tiny crown
                val crown = Path().apply {
                    moveTo(d * .29f, d * .23f); lineTo(d * .30f, d * .06f); lineTo(d * .41f, d * .14f)
                    lineTo(d * .50f, d * .025f); lineTo(d * .59f, d * .14f); lineTo(d * .70f, d * .06f)
                    lineTo(d * .71f, d * .23f); close()
                }
                drawPath(crown, Color(0xFFF2C65D))
                drawPath(crown, ink, style = Stroke(width = d * .025f))
            }
        }
    }
}

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
                    .border(if(draft.petColor == index) 3.dp else 1.dp, if(draft.petColor == index) dark else border, CircleShape)
                    .clickable(onClickLabel = "Choose colour ${index + 1}") { draft = draft.copy(petColor = index) })
            }
        }
        Text("Eyes", color = dark, fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("Classic", "Sparkle", "Happy"), draft.petEyes) { draft = draft.copy(petEyes = it) }
        Text("Hat", color = dark, fontWeight = FontWeight.Medium)
        ChoiceRow(listOf("None", "Beanie", "Party", "Crown"), draft.petHat) { draft = draft.copy(petHat = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show companion in the app", Modifier.weight(1f), color = dark)
            Switch(draft.showPet, { draft = draft.copy(showPet = it) })
        }
        Button(onClick = { onSave(draft) }, enabled = draft != profile, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Save customization") }
    }
}

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Surface(shape = RoundedCornerShape(14.dp), color = if(index == selected) dark else wash,
                modifier = Modifier.weight(1f).clickable { onSelect(index) }) {
                Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if(index == selected) Color.White else dark, fontSize = 12.sp)
                }
            }
        }
    }
}
