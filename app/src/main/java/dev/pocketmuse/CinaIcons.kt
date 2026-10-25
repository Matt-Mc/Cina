package dev.pocketmuse

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Composable
internal fun CinaIcon(symbol: String, modifier: Modifier = Modifier, tint: Color = Ink) {
    val vector=remember(symbol) {
        ImageVector.Builder("Cina control",24.dp,24.dp,24f,24f).apply {
            path(stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,strokeLineCap=StrokeCap.Round) {
                when(symbol) {
                    "☰" -> { moveTo(4f,6f);lineTo(20f,6f);moveTo(4f,12f);lineTo(20f,12f);moveTo(4f,18f);lineTo(20f,18f) }
                    "‹" -> { moveTo(15f,5f);lineTo(8f,12f);lineTo(15f,19f) }
                    "›" -> { moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f) }
                    "＋" -> { moveTo(12f,4f);lineTo(12f,20f);moveTo(4f,12f);lineTo(20f,12f) }
                    "↑" -> { moveTo(12f,20f);lineTo(12f,4f);moveTo(5f,11f);lineTo(12f,4f);lineTo(19f,11f) }
                    "■" -> { moveTo(6f,6f);lineTo(18f,6f);lineTo(18f,18f);lineTo(6f,18f);close() }
                    else -> { moveTo(4f,12f);lineTo(5f,12f);moveTo(11.5f,12f);lineTo(12.5f,12f);moveTo(19f,12f);lineTo(20f,12f) }
                }
            }
        }.build()
    }
    Icon(vector,contentDescription=null,modifier=modifier,tint=tint)
}
