package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * A compact pitch-accent diagram mirroring the WebView's pitch graph.
 * Given a single [downsteps] position, moras before it are high and moras
 * from it onward are low; a polyline connects the mora dots and a dashed
 * tail extends low until the end of the text.
 */
@Composable
fun PitchAccentDiagram(
    text: String,
    downsteps: List<Int>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val downstep = downsteps.minOrNull()
    // Split into moras: treat each char as a mora (simple approximation).
    val morae = text.toList()
    val n = morae.size
    if (n == 0) return
    val slotW = 18f
    Canvas(
        modifier = modifier.size(width = (n * slotW).dp, height = 34.dp),
    ) {
        val baseY = size.height * 0.78f
        val highY = size.height * 0.22f
        val lowY = size.height * 0.58f
        val lineW = size.height * 0.035f

        fun isHigh(i: Int): Boolean = downstep == null || i < downstep

        fun dotX(i: Int) = slotW * (i + 0.5f)
        fun dotY(i: Int) = if (isHigh(i)) highY else lowY

        // Contour polyline connecting all mora dots.
        val line = Path()
        for (i in 0 until n) {
            if (i == 0) line.moveTo(dotX(i), dotY(i)) else line.lineTo(dotX(i), dotY(i))
        }
        drawPath(
            path = line,
            color = accentColor,
            style = Stroke(width = lineW, cap = StrokeCap.Round, pathEffect = if (downstep == null) PathEffect.dashPathEffect(floatArrayOf(12f, 12f)) else null),
        )

        // Dashed tail projecting low past the last mora when there is a downstep.
        if (downstep != null) {
            val tailStart = dotX(n - 1)
            val endX = size.width + 6f
            drawLine(
                color = accentColor,
                start = Offset(tailStart, lowY),
                end = Offset(endX, lowY),
                strokeWidth = lineW,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }

        // Bars + dots per mora.
        for (i in 0 until n) {
            val x = dotX(i)
            val y = dotY(i)
            drawLine(
                color = accentColor.copy(alpha = 0.55f),
                start = Offset(x, baseY),
                end = Offset(x, y),
                strokeWidth = lineW * 0.6f,
            )
            drawCircle(
                color = accentColor,
                radius = lineW * 1.6f,
                center = Offset(x, y),
            )
        }
    }
}