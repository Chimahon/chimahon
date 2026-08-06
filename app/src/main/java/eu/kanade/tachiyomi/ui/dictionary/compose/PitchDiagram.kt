package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Split [text] into Japanese moras, combining small kana with the previous
 * mora (matches the WebView's `getMorae` + `SMALL_KANA_SET`).
 */
internal fun splitMorae(text: String): List<String> {
    val smallKana = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ".toSet()
    val morae = mutableListOf<String>()
    for (c in text) {
        if (c in smallKana && morae.isNotEmpty()) {
            morae[morae.size - 1] = morae.last() + c
        } else {
            morae.add(c.toString())
        }
    }
    return morae
}

/**
 * A compact pitch-accent diagram mirroring the WebView's pitch graph.
 * Given a single downstep position, mora 0 is low except for heiban (0),
 * and moras from the accent position onward drop low.
 */
@Composable
fun PitchAccentDiagram(
    text: String,
    downsteps: List<Int>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {    if (text.isBlank()) return
    val accent = downsteps.minOrNull()
    val morae = splitMorae(text)
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

        fun isHigh(i: Int): Boolean = accent == null || isMoraPitchHigh(i, accent)

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
            style = Stroke(width = lineW, cap = StrokeCap.Round, pathEffect = if (accent == null) PathEffect.dashPathEffect(floatArrayOf(12f, 12f)) else null),
        )

        // Dashed tail projecting low past the last mora when there is a downstep.
        if (accent != null) {
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

/**
 * Renders the reading kana with pitch-line overlays, mirroring the WebView's
 * `.pronunciation-text` (createPronunciationText). Each mora is drawn with a
 * top line when high; a right drop + follow line when high drops to next-low.
 */
@Composable
fun PitchTextLine(
    text: String,
    positions: List<Int>,
    color: Color,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    val accent = positions.minOrNull()
    val morae = splitMorae(text)
    if (morae.isEmpty()) return
    val annotation = color
    val ls = (fontSize * 0.9).sp

    Row(modifier = modifier) {
        morae.forEachIndexed { i, mora ->
            val high = accent == null || isMoraPitchHigh(i, accent)
            val highNext = accent == null || isMoraPitchHigh(i + 1, accent)
            val dropDown = high && !highNext
            Text(
                text = mora,
                fontSize = ls,
                color = color,
                modifier = Modifier
                    .padding(end = if (dropDown) 2.dp else 0.dp)
                    .drawBehind {
                        if (high) {
                            val lineW = ls.toPx() * 0.1f
                            drawLine(
                                color = annotation,
                                start = Offset.Zero,
                                end = Offset(size.width, 0f),
                                strokeWidth = lineW,
                            )
                            if (dropDown) {
                                drawLine(
                                    color = annotation,
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, lineW * 4f),
                                    strokeWidth = lineW,
                                )
                            }
                        }
                    },
            )
        }
    }
}