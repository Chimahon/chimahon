package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.text.TextStyle
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

public data class RubyColumnBox(val left: Float, val top: Float, val width: Int, val height: Int, val baseline: Int)

/** Default ruby alignment: widen the intrinsic column, allowing bounded overhang onto text. */
internal fun rubyIntrinsicExtra(baseSlot: Float, annotation: Float, annotationFont: Float,
    preceding: Float = 0f, following: Float = 0f): Float {
    val extra = (annotation - baseSlot).coerceAtLeast(0f)
    val overhang = minOf(extra / 2f, annotationFont / 2f)
    return extra - minOf(overhang, preceding / 2f) - minOf(overhang, following / 2f)
}

/** Union logical/ink extents, excluding unused font-box padding. Coordinates share the drawing origin. */
internal fun rubyColumnBox(baseTop: Float, baseBottom: Float, baseBaseline: Float,
    readingTop: Float, readingBottom: Float, columnWidth: Int, baseWidth: Float, readingWidth: Float): RubyColumnBox {
    val widest = maxOf(baseWidth, readingWidth)
    val left = floor(minOf(0f, (columnWidth - widest) / 2f))
    val right = ceil(maxOf(columnWidth.toFloat(), (columnWidth + widest) / 2f))
    val top = floor(minOf(baseTop, readingTop))
    val bottom = ceil(maxOf(baseBottom, readingBottom))
    return RubyColumnBox(left, top, (right - left).toInt(), (bottom - top).toInt(),
        (baseBaseline - top).roundToInt())
}

/** Standalone headers own a CSS line box; the font's full ascent/descent are not extra padding. */
public fun RubyMetrics.standaloneBox(): RubyColumnBox {
    val baseBaseline = baseOffsetPx + baseLayout.firstBaseline
    val readingBaseline = readingOffsetPx + readingLayout.firstBaseline
    val baseLeading = (baseLayout.lineHeightPx - baseLayout.ascentPx - baseLayout.descentPx) / 2f
    val baseTop = baseBaseline - baseLayout.ascentPx - baseLeading
    val readingLeading = (readingLayout.lineHeightPx - readingLayout.ascentPx - readingLayout.descentPx) / 2f
    val readingTop = readingBaseline - (readingLayout.emAscentPx ?: (readingLayout.ascentPx + readingLeading))
    val readingBottom = readingBaseline + (readingLayout.emDescentPx ?: (readingLayout.descentPx + readingLeading))
    return rubyColumnBox(
        minOf(baseTop, baseOffsetPx + baseLayout.inkBounds.top),
        maxOf(baseTop + baseLayout.lineHeightPx, baseOffsetPx + baseLayout.inkBounds.bottom),
        baseBaseline,
        minOf(readingTop, readingOffsetPx + readingLayout.inkBounds.top),
        maxOf(readingBottom, readingOffsetPx + readingLayout.inkBounds.bottom),
        widthPx, baseLayout.advancePx, readingLayout.advancePx)
}

/** An atomic ruby column reserves space above the shared base baseline, including annotation ink. */
public fun RubyTextRenderer.column(
    base: String,
    reading: String,
    style: TextStyle,
    readingStyle: TextStyle,
    baseSegments: List<RubyTextSegment> = emptyList(),
    readingSegments: List<RubyTextSegment> = emptyList(),
): RubyMetrics {
    val baseResource = resources(style)
    val readingResource = resources(readingStyle)
    val baseRun = prepare(base, style, baseSegments, baseResource)
    val readingRun = prepare(reading, readingStyle, readingSegments, readingResource)
    val height = ceil(readingRun.heightPx).toInt().coerceAtLeast(1)
    val baseEmAscent = baseRun.emAscentPx
    val readingEmDescent = readingRun.emDescentPx
    // Blink places the annotation's em-box bottom on the base em-box top (ruby_utils.cc PlaceLines).
    val readingShift = if (baseEmAscent != null && readingEmDescent != null) {
        height + baseRun.firstBaseline - baseEmAscent - readingEmDescent - readingRun.firstBaseline
    } else {
        val halfLeading = (baseResource.lineHeightPx - baseResource.paint.textSize) / 2f
        (readingResource.paint.textSize - halfLeading).coerceAtLeast(0f)
    }
    return RubyMetrics(
        widthPx = baseRun.size.width,
        heightPx = height,
        readingOffsetPx = readingShift,
        baseOffsetPx = height.toFloat(),
        baseText = base,
        readingText = reading,
        baseLayout = baseRun,
        readingLayout = readingRun,
        readingStyle = readingStyle,
    )
}
