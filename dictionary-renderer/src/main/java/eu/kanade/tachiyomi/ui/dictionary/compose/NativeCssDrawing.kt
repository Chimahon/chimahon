package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sqrt

/** Centered, default-ellipse gradients with percentage stops (CSS Images 3). */
internal fun radialColorStops(raw: String?): List<Pair<Int, Float>>? {
    val text = raw?.trim() ?: return null
    if (!text.startsWith("radial-gradient(") || !text.endsWith(')')) return null
    val body = text.substringAfter('(').dropLast(1)
    val segments = ArrayList<String>()
    var depth = 0
    var start = 0
    for (i in body.indices) when (body[i]) {
        '(' -> depth++
        ')' -> depth--
        ',' -> if (depth == 0) { segments.add(body.substring(start, i).trim()); start = i + 1 }
    }
    segments.add(body.substring(start).trim())
    if (segments.size < 2) return null
    val stops = ArrayList<Pair<Int, Float>>(segments.size)
    for ((index, segment) in segments.withIndex()) {
        val split = segment.lastIndexOf(' ')
        val hasPosition = split > 0 && segment.endsWith('%')
        val color = parseCssColor2(if (hasPosition) segment.substring(0, split).trim() else segment) ?: return null
        val position = if (hasPosition) segment.substring(split + 1).dropLast(1).toFloatOrNull()?.div(100f) ?: return null
            else when (index) { 0 -> 0f; segments.lastIndex -> 1f; else -> return null }
        if (position !in 0f..1f || (stops.isNotEmpty() && position < stops.last().second)) return null
        stops.add(color.toArgb() to position)
    }
    return stops
}

internal fun radialBackground(raw: String?, width: Int, height: Int): Shader? {
    if (width <= 0 || height <= 0) return null
    val stops = radialColorStops(raw) ?: return null
    return RadialGradient(0f, 0f, 1f, stops.map { it.first }.toIntArray(),
        stops.map { it.second }.toFloatArray(), Shader.TileMode.CLAMP).apply {
        // The default ending ellipse reaches each corner of its reference box.
        setLocalMatrix(Matrix().apply {
            setScale(width / sqrt(2f), height / sqrt(2f))
            postTranslate(width / 2f, height / 2f)
        })
    }
}

/** Flat paint-stream boundaries keep clipping around descendants without a layout subtree. */
internal class NativeCircleClip(override val x: Int, override val y: Int, override val width: Int,
    override val height: Int, private val begin: Boolean) : NativeGlossaryItem {
    private val path = if (begin) Path().apply {
        addCircle(x + width / 2f, y + height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
    } else null
    override fun moved(dx: Int, dy: Int) = NativeCircleClip(x + dx, y + dy, width, height, begin)
    override fun draw(canvas: Canvas, paint: Paint) {
        if (begin) { canvas.save(); canvas.clipPath(checkNotNull(path)) } else canvas.restore()
    }
}
