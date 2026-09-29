package dev.pocketmuse

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

internal val petColors = listOf(
    Color(0xFFFFEAB6), Color(0xFFBFE1CE), Color(0xFFFFC9C0),
    Color(0xFFC9D8F3), Color(0xFFDCCBEC), Color(0xFFF4D5B2)
)

internal fun companionColor(profile: YouProfile): Color = normalizedPetColor(profile.petCustomColor)
    .takeIf { it.isNotEmpty() }?.let { Color((0xFF000000L or it.toLong(16)).toInt()) }
    ?: petColors[profile.petColor.coerceIn(petColors.indices)]

@Composable
fun CompanionBubble(profile: YouProfile, diameter: Dp, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "Companion motion")
    val bob by motion.animateFloat(0f, -2f,
        infiniteRepeatable(tween(1900), RepeatMode.Reverse), label = "Float")
    val blink by motion.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4800
        1f at 0; 1f at 4050; .08f at 4160; 1f at 4320; 1f at 4800
    }), label = "Blink")
    Canvas(modifier.size(diameter).semantics {
        contentDescription = "${profile.petName.ifBlank { "Cina" }} companion"
    }) {
        // A shared design space keeps fine details sharp at every display density.
        val scale = size.minDimension / 100f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            withTransform({ scale(1f, .2f, pivot = Offset(50f, 91f)) }) {
                drawCircle(Brush.radialGradient(
                    listOf(Color(0xFF39483D).copy(alpha = .18f), Color.Transparent),
                    center = Offset(50f, 91f), radius = 33f
                ), 33f, Offset(50f, 91f))
            }
            withTransform({ translate(0f, bob) }) {
                drawCompanion(profile, blink)
            }
        }
    }
}

private fun DrawScope.drawCompanion(profile: YouProfile, blink: Float) {
    val base = companionColor(profile)
    val darkBody = base.luminance() < .18f
    val ink = if (darkBody) Color(0xFFFFF4DA) else Color(0xFF283D35)
    val body = Path().apply {
        moveTo(50f, 14f)
        cubicTo(73f, 14f, 88f, 30f, 88f, 52f)
        cubicTo(88f, 76f, 74f, 89f, 50f, 89f)
        cubicTo(26f, 89f, 12f, 76f, 12f, 52f)
        cubicTo(12f, 30f, 27f, 14f, 50f, 14f)
        close()
    }
    drawPath(body, Brush.radialGradient(
        colorStops = arrayOf(0f to lerp(base, Color.White, .72f),
            .46f to base, .80f to lerp(base, Color(0xFFAD895F), .24f),
            1f to lerp(base, Color(0xFF283D35), .32f)), center = Offset(34f, 29f), radius = 70f
    ))
    clipPath(body) {
        // Broad, translucent reflections give the surface a soft ceramic finish.
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .65f), Color.Transparent),
            center = Offset(33f, 25f), radius = 28f), 28f, Offset(33f, 25f))
        drawPath(body, Brush.linearGradient(listOf(Color.White.copy(alpha = .8f),
            base.copy(alpha = .05f), ink.copy(alpha = .14f)), Offset(25f, 14f), Offset(72f, 87f)),
            style = Stroke(1.5f))
        if (profile.petBlush) listOf(27f, 73f).forEach { x ->
            drawCircle(Brush.radialGradient(listOf(Color(0xFFDF8E78).copy(alpha = .30f),
                Color.Transparent), center = Offset(x, 62f), radius = 9f), 9f, Offset(x, 62f))
        }
    }
    listOf(37f, 63f).forEach { x ->
        if (profile.petEyes == 5) {
            withTransform({ scale(1f, blink, pivot = Offset(x, 51f)) }) {
                drawPath(Path().apply {
                    moveTo(x, 57f)
                    cubicTo(x - 13f, 49f, x - 4f, 41f, x, 47f)
                    cubicTo(x + 4f, 41f, x + 13f, 49f, x, 57f)
                    close()
                }, if (darkBody) Color(0xFFFFBDCA) else Color(0xFFA6415D))
            }
        } else if (profile.petEyes == 3 || (profile.petEyes == 4 && x == 63f)) {
            drawPath(Path().apply {
                moveTo(x - 5f, 50f)
                quadraticTo(x, 55f, x + 5f, 50f)
            }, ink, style = Stroke(2.5f, cap = StrokeCap.Round))
        } else if (profile.petEyes == 2) {
            drawPath(Path().apply {
                moveTo(x - 5f, 52f)
                cubicTo(x - 3f, 45.5f, x + 3f, 45.5f, x + 5f, 52f)
            }, ink, style = Stroke(3f, cap = StrokeCap.Round))
        } else {
            val eyeHeight = if (profile.petEyes == 1) 13f else 16f
            withTransform({ scale(1f, blink, pivot = Offset(x, 51f)) }) {
                drawRoundRect(Brush.linearGradient(if (darkBody) listOf(Color.White, ink)
                    else listOf(Color(0xFF426052), Color(0xFF172D25)),
                    Offset(x - 4f, 43f), Offset(x + 4f, 60f)),
                    Offset(x - 4.8f, 51f - eyeHeight / 2), Size(9.6f, eyeHeight), CornerRadius(4.8f))
                drawOval(Color.White.copy(alpha = .9f), Offset(x - 2.8f, 51f - eyeHeight / 2 + 2f), Size(2.8f, 3.6f))
                if (profile.petEyes == 1) {
                    drawCircle(Color(0xFFE6F4DF), 1.4f, Offset(x + 2f, 54f))
                }
            }
        }
    }
    when (profile.petMouth) {
        1 -> {
            drawPath(Path().apply {
                moveTo(43f, 63f); lineTo(57f, 63f)
                cubicTo(56f, 74f, 44f, 74f, 43f, 63f); close()
            }, ink)
            drawLine(if (darkBody) Color(0xFF283D35) else Color.White,
                Offset(46f, 64.5f), Offset(54f, 64.5f), 2f, StrokeCap.Round)
        }
        2 -> drawOval(ink, Offset(47f, 62f), Size(6f, 8f))
        3 -> drawLine(ink, Offset(46f, 65f), Offset(54f, 65f), 1.8f, StrokeCap.Round)
        else -> drawPath(Path().apply {
            moveTo(45f, 64f)
            cubicTo(47.5f, 67f, 52.5f, 67f, 55f, 64f)
        }, ink.copy(alpha = .85f), style = Stroke(1.8f, cap = StrokeCap.Round))
    }
    when (profile.petAccessory) {
        1 -> {
            listOf(37f, 63f).forEach { x ->
                drawCircle(Color.White.copy(alpha = .12f), 10f, Offset(x, 51f))
                drawCircle(ink, 10f, Offset(x, 51f), style = Stroke(1.5f))
            }
            drawLine(ink, Offset(47f, 50f), Offset(53f, 50f), 1.5f)
            drawLine(ink, Offset(20f, 48f), Offset(27f, 50f), 1.5f)
            drawLine(ink, Offset(73f, 50f), Offset(80f, 48f), 1.5f)
        }
        2 -> listOf(25f, 71f).forEach { x ->
            listOf(Offset(x, 61f), Offset(x + 4f, 63f), Offset(x - 3f, 65f)).forEach {
                drawCircle(ink.copy(alpha = .45f), 1f, it)
            }
        }
    }
    drawCompanionHat(profile.petHat, ink)
}

private fun DrawScope.drawCompanionHat(hat: Int, ink: Color) {
    when (hat) {
        4 -> {
            drawPath(Path().apply {
                moveTo(25f, 25f); cubicTo(13f, 14f, 39f, 3f, 57f, 9f)
                cubicTo(81f, 6f, 83f, 27f, 66f, 27f); close()
            }, Brush.linearGradient(listOf(Color(0xFFA8BCAF), Color(0xFF436B57)),
                Offset(31f, 7f), Offset(66f, 29f)))
            drawRoundRect(Color(0xFF365944), Offset(29f, 23f), Size(42f, 5f), CornerRadius(2f))
            drawLine(Color(0xFF365944), Offset(51f, 10f), Offset(54f, 5f), 3f, StrokeCap.Round)
        }
        5 -> {
            drawPath(Path().apply { moveTo(50f, 22f); quadraticTo(48f, 13f, 54f, 7f) },
                Color(0xFF49734C), style = Stroke(2f, cap = StrokeCap.Round))
            drawPath(Path().apply {
                moveTo(50f, 16f); cubicTo(33f, 17f, 34f, 5f, 35f, 5f)
                cubicTo(46f, 4f, 51f, 10f, 50f, 16f); close()
                moveTo(51f, 12f); cubicTo(52f, 3f, 62f, 3f, 66f, 5f)
                cubicTo(64f, 13f, 58f, 16f, 51f, 12f); close()
            }, Brush.linearGradient(listOf(Color(0xFFB9D68E), Color(0xFF548455)),
                Offset(40f, 4f), Offset(56f, 18f)))
        }
        6 -> {
            drawPath(Path().apply {
                moveTo(60f, 23f); cubicTo(40f, 1f, 38f, 30f, 60f, 23f)
                cubicTo(83f, 6f, 83f, 37f, 60f, 23f); close()
            }, Brush.linearGradient(listOf(Color(0xFFF1B6BC), Color(0xFFB95B78)),
                Offset(45f, 10f), Offset(76f, 32f)))
            drawRoundRect(Color(0xFFAE526E), Offset(57f, 18f), Size(7f, 10f), CornerRadius(3f))
        }
        1 -> {
            val cap = Path().apply {
                moveTo(27f, 25f); cubicTo(28f, 0f, 70f, 0f, 73f, 25f); close()
            }
            drawPath(cap, Brush.linearGradient(listOf(Color(0xFFAD938B), Color(0xFF735E59)),
                Offset(33f, 7f), Offset(66f, 29f)))
            clipPath(cap) {
                for (x in 33..69 step 6) {
                    drawLine(Color.White.copy(alpha = .16f), Offset(x.toFloat(), 5f),
                        Offset(x - 2f, 26f), 1.2f, StrokeCap.Round)
                }
            }
            drawRoundRect(Color(0xFF93776B), Offset(25f, 21f), Size(50f, 9f), CornerRadius(4f))
            drawLine(Color(0xFFD9BDB0), Offset(29f, 23f), Offset(70f, 23f), 1f, StrokeCap.Round)
            drawRoundRect(Color(0xFFF4E9D5), Offset(57f, 23f), Size(6f, 5f), CornerRadius(1f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFC5AAA0), Color(0xFF93776B)),
                Offset(48f, 7f), 5f), 4.5f, Offset(50f, 8f))
        }
        2 -> {
            val cone = Path().apply {
                moveTo(50f, 5f); lineTo(68f, 28f); quadraticTo(50f, 32f, 32f, 28f); close()
            }
            drawPath(cone, Brush.linearGradient(listOf(Color(0xFFF0B5A1), Color(0xFFC56956)),
                Offset(36f, 10f), Offset(65f, 30f)))
            clipPath(cone) {
                drawLine(Color(0xFFFFE8B3), Offset(36f, 10f), Offset(66f, 22f), 4f)
                drawLine(Color(0xFFFFE8B3), Offset(31f, 21f), Offset(60f, 33f), 4f)
            }
            drawCircle(Color(0xFFFFE8B3), 3.5f, Offset(50f, 6f))
        }
        3 -> {
            val crown = Path().apply {
                moveTo(30f, 27f); lineTo(28f, 9f); quadraticTo(28f, 7f, 30f, 9f)
                lineTo(40f, 17f); lineTo(49f, 5f); quadraticTo(50f, 3f, 51f, 5f)
                lineTo(60f, 17f); lineTo(70f, 9f); quadraticTo(72f, 7f, 72f, 9f)
                lineTo(70f, 27f); quadraticTo(50f, 31f, 30f, 27f); close()
            }
            drawPath(crown, Brush.linearGradient(listOf(Color(0xFFFFE7A0), Color(0xFFD4A242)),
                Offset(38f, 6f), Offset(62f, 31f)))
            drawPath(crown, Color(0xFFB58631).copy(alpha = .5f), style = Stroke(.8f))
            drawLine(Color(0xFFFFEEC0), Offset(33f, 25f), Offset(67f, 25f), 1.5f, StrokeCap.Round)
            drawCircle(ink.copy(alpha = .8f), 2.3f, Offset(50f, 20f))
        }
    }
}
