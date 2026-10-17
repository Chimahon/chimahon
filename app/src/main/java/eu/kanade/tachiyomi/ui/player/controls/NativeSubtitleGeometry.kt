package eu.kanade.tachiyomi.ui.player.controls

import androidx.compose.ui.geometry.Rect
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** One native render snapshot. Offsets are UTF-16; times already use the video timeline. */
@Serializable
internal data class NativeSubtitleGeometry(
    val version: Int,
    val w: Int,
    val h: Int,
    val events: List<NativeSubtitleEvent>,
) {
    fun lookupAt(x: Float, y: Float, width: Int, height: Int, track: String): SubtitleLookupRequest? {
        if (version != 1 || w <= 0 || h <= 0 || width <= 0 || height <= 0) return null
        val scaleX = width.toFloat() / w
        val scaleY = height.toFloat() / h
        for (event in events.asReversed()) {
            if (!event.start.isFinite() || !event.end.isFinite() || event.end <= event.start) continue
            val spans = event.spans.filter {
                it.start >= 0 && it.end in (it.start + 1)..event.text.length && it.w > 0 && it.h > 0
            }
            val selected = spans.filter { it.rect(scaleX, scaleY).contains(androidx.compose.ui.geometry.Offset(x, y)) }
                .minByOrNull { it.w.toLong() * it.h } ?: continue
            if (!isLookupStartChar(event.text[selected.start])) continue
            val lookup = extractOcrLookupString(event.text, selected.start).take(80).trim()
            if (lookup.isBlank()) continue
            val rectangles = spans.map { NativeSubtitleRectangle(it.start, it.end, it.rect(scaleX, scaleY)) }
            val bounds = rectangles.map { it.bounds }.reduce { a, b ->
                Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            }
            return SubtitleLookupRequest(
                lookupString = lookup,
                fullText = event.text,
                charOffset = selected.start,
                tapCharOffset = selected.start,
                lineText = event.text,
                lineIndex = 0,
                lineStartOffset = 0,
                anchorX = bounds.left,
                anchorY = bounds.top,
                anchorWidth = bounds.width,
                anchorHeight = bounds.height,
                lineLeft = bounds.left,
                lineTop = bounds.top,
                lineWidth = bounds.width,
                lineHeight = bounds.height,
                cueStartSeconds = event.start,
                cueEndSeconds = event.end,
                nativeCueKey = "$track:${event.id}:${event.start}:${event.end}",
                nativeRectangles = rectangles,
            )
        }
        return null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(value: String?): NativeSubtitleGeometry? = value?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString<NativeSubtitleGeometry>(it) }.getOrNull()
        }
    }
}

@Serializable
internal data class NativeSubtitleEvent(
    val id: Int,
    val text: String,
    val start: Double,
    val end: Double,
    val spans: List<NativeSubtitleSpan>,
)

@Serializable
internal data class NativeSubtitleSpan(val start: Int, val end: Int, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun rect(scaleX: Float, scaleY: Float) = Rect(x * scaleX, y * scaleY, (x.toFloat() + w) * scaleX, (y.toFloat() + h) * scaleY)
}

internal data class NativeSubtitleRectangle(val start: Int, val end: Int, val bounds: Rect)

/** Join selected glyph ink into one highlight per visual line, including rotated vertical text. */
internal fun List<NativeSubtitleRectangle>.mergedHighlightBounds(start: Int, end: Int): List<Rect> {
    val vertical = zipWithNext().count { (a, b) ->
        abs(a.bounds.center.y - b.bounds.center.y) > abs(a.bounds.center.x - b.bounds.center.x)
    } > (size - 1) / 2
    val lines = mutableListOf<Rect>()
    filter { it.start < end && it.end > start }.forEach { glyph ->
        val rect = glyph.bounds
        val line = lines.indexOfFirst {
            if (vertical) {
                minOf(it.right, rect.right) - maxOf(it.left, rect.left) >= minOf(it.width, rect.width) / 2
            } else {
                minOf(it.bottom, rect.bottom) - maxOf(it.top, rect.top) >= minOf(it.height, rect.height) / 2
            }
        }
        if (line < 0) {
            lines += rect
        } else {
            val previous = lines[line]
            lines[line] = Rect(
                minOf(previous.left, rect.left), minOf(previous.top, rect.top),
                maxOf(previous.right, rect.right), maxOf(previous.bottom, rect.bottom),
            )
        }
    }
    return lines
}
