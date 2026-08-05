package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A compact pitch-accent diagram drawn entirely in Compose, mirroring the
 * WebView's pitch graph. Each mora of [text] gets a bar; a polyline on top
 * marks the high/low contour. Moras from the first [downstepPositions] entry
 * onward are low, so the polyline drops by a full step across the downstep.
 */
@Composable
fun PitchAccentDiagram(
    text: String,
    downstepPositions: List<Int>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val minDownstep = downstepPositions.minOrNull()
    Canvas(
        modifier = modifier.size(width = (text.length * 18).dp, height = 32.dp),
    ) {
        val n = text.length
        if (n == 0) return@Canvas
        val moraW = size.width / n
        val baseY = size.height * 0.85f
        val highY = size.height * 0.28f
        val lowY = size.height * 0.62f

        fun isHigh(i: Int): Boolean = minDownstep == null || i < minDownstep

        fun dotCenter(i: Int) = Offset(
            x = moraW * (i + 0.5f),
            y = if (isHigh(i)) highY else lowY,
        )

        // accent polyline connecting the mora dots (naturally drops at downstep)
        val line = Path()
        line.moveTo(dotCenter(0).x, dotCenter(0).y)
        for (i in 1 until n) line.lineTo(dotCenter(i).x, dotCenter(i).y)
        drawPath(
            path = line,
            color = accentColor,
            style = Stroke(width = size.height * 0.035f, cap = StrokeCap.Round),
        )

        // bars + dots per mora
        for (i in 0 until n) {
            val cx = moraW * (i + 0.5f)
            val top = if (isHigh(i)) highY else lowY
            drawLine(
                color = accentColor.copy(alpha = 0.6f),
                start = Offset(cx, baseY),
                end = Offset(cx, top),
                strokeWidth = size.height * 0.02f,
            )
            drawCircle(
                color = accentColor,
                radius = size.height * 0.05f,
                center = dotCenter(i),
            )
        }
    }
}