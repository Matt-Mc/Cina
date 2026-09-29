package dev.pocketmuse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun CompanionColorPicker(profile: YouProfile, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(companionColor(profile).toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var brightness by remember { mutableFloatStateOf(initial[2]) }
    fun hexFromHsv() = "%06X".format(java.util.Locale.ROOT,
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF)
    var hex by remember { mutableStateOf(hexFromHsv()) }
    val validHex = normalizedPetColor(hex)
    val previewHex = hexFromHsv()
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = Color(0xFFFAF9F6),
        titleContentColor = Color(0xFF26312C),
        textContentColor = Color(0xFF536359),
        title = { Text("Create a colour") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().background(Color(0xFFF7F2E9), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center) {
                    CompanionBubble(profile.copy(petCustomColor = previewHex), 100.dp)
                }
                ColorSlider("Hue", hue, 360f, listOf(Color.Red, Color.Yellow, Color.Green,
                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) {
                    hue = it; hex = hexFromHsv()
                }
                ColorSlider("Saturation", saturation, 1f,
                    listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness))) {
                    saturation = it; hex = hexFromHsv()
                }
                ColorSlider("Brightness", brightness, 1f,
                    listOf(Color.Black, Color.hsv(hue, saturation, 1f))) {
                    brightness = it; hex = hexFromHsv()
                }
                OutlinedTextField(value = hex, onValueChange = { input ->
                    hex = input.removePrefix("#").take(6).uppercase(java.util.Locale.ROOT)
                    normalizedPetColor(hex).takeIf { it.isNotEmpty() }?.let {
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV((0xFF000000L or it.toLong(16)).toInt(), hsv)
                        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                    }
                }, label = { Text("Hex colour") }, prefix = { Text("#") }, singleLine = true,
                    isError = validHex.isEmpty(), supportingText = {
                        Text(if (validHex.isEmpty()) "Enter 6 characters: 0–9 or A–F." else "Use an exact colour, such as BFE1CE.")
                    }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onApply(validHex) }, enabled = validHex.isNotEmpty()) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ColorSlider(label: String, value: Float, maximum: Float, colours: List<Color>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (maximum == 360f) "${value.roundToInt()}°" else "${(value * 100).roundToInt()}%")
    }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp)
            .background(Brush.horizontalGradient(colours), RoundedCornerShape(4.dp)))
        Slider(value = value, onValueChange = onChange, valueRange = 0f..maximum,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
    }
}
