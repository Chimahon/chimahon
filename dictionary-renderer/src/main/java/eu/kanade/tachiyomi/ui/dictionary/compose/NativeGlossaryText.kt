package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.text.BoringLayout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.IdentityHashMap
import chimahon.diagnostics.LookupProfiler
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Worker-owned font/style resources, not a cache of words or previously opened documents. */
public class NativeGlossaryResources(
    val density: Density,
    val direction: LayoutDirection,
    resolver: FontFamily.Resolver,
    private val collectStageTimings: Boolean = false,
) {
    private companion object {
        val EMPTY_BOX = BoxStyle()

        /** Em budget for trying BoringLayout first; actual advance still decides. */
        const val SINGLE_LINE_PROBE_FACTOR = 2f
    }
    val ruby = RubyTextRenderer(density, direction, resolver)
    private val lineBreakConfig = if (Build.VERSION.SDK_INT >= 33) LineBreakConfig.Builder()
        .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NORMAL)
        .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE).build() else null
    internal val cascade = NativeCssCascade(collectStageTimings)
    internal val stageTimings: Boolean get() = cascade.collectStageTimings
    private class TypographyEntry(val modifier: GlossaryTypography) {
        val inherited = IdentityHashMap<NativeTextStyle, NativeTextStyle>()
        var readings: HashMap<TextStyle, TextStyle>? = null
    }
    private val typography = IdentityHashMap<NativeCssDeclarations, TypographyEntry>()
    private class BoxEntry(val fixed: BoxStyle?) {
        val relative = if (fixed == null) HashMap<Float, BoxStyle>() else null
    }
    private val boxes = IdentityHashMap<NativeCssDeclarations, BoxEntry>()
    private val profiles = ArrayList<NativeTextProfile>()
    private val computedStyles = HashMap<TextStyle, NativeTextStyle>()
    // Same style instances recur across runs; identity avoids re-hashing their spans.
    private val identityStyles = IdentityHashMap<TextStyle, NativeTextStyle>()
    private val markers = IdentityHashMap<NativeTextProfile, MutableMap<String, NativeListMarkerPaint>>()
    var typographyMisses = 0
    var boxMisses = 0
    var profileMisses = 0
    /** Text appends into the SpannableStringBuilder; there are far more of these than elements. */
    var appends = 0
    /** Wall time spent inside CSS declaration resolution and merge, summed over all elements. */
    var declarationsNanos = 0L
    /** Wall time spent in the resource lookups (typography, box, profile), summed. */
    var resourceNanos = 0L
    var profilingId = 0L
        set(value) {
            field = value
            ruby.profilingId = value
            cascade.collectStageTimings = collectStageTimings || value != 0L
        }
    var paragraphs = 0
    var singleLineParagraphs = 0
    var plainTextParagraphs = 0
    var rubyPairs = 0
    var cssMatches = 0
    var elements = 0
    var tables = 0
    var lineLayoutNanos = 0L
    var paragraphFinishNanos = 0L
    /** Post-layout geometry includes decoration and ruby placement below; do not sum them. */
    var paragraphGeometryNanos = 0L
    var decorationNanos = 0L
    var rubyPlacementNanos = 0L
    val ancestorNanos: Long get() = cascade.ancestorNanos
    var rubyLowerNanos = 0L
    var tableNanos = 0L
    var listMarkers = 0
    private val fontValidity = NativeFontValidity {
        profiles.any { it.resource.font.isStale }
    }
    val hasStaleFonts: Boolean get() = fontValidity.stale

    private fun typographyEntry(css: NativeCssDeclarations): TypographyEntry {
        var entry = typography[css]
        if (entry == null) {
            typographyMisses++
            entry = TypographyEntry(GlossaryTypography.read(css))
            typography[css] = entry
        }
        return entry
    }

    internal fun nativeStyle(style: TextStyle): NativeTextStyle {
        identityStyles[style]?.let { return it }
        return computedStyles.getOrPut(style) { NativeTextStyle(style, this) }
            .also { identityStyles[style] = it }
    }

    internal fun profile(style: NativeTextStyle): NativeTextProfile = style.profile

    internal fun box(css: NativeCssDeclarations, style: NativeTextStyle): BoxStyle = box(css, style.textStyle)

    internal fun marker(text: String, style: NativeTextStyle): NativeListMarkerPaint = marker(text, style.profile)

    fun style(base: TextStyle, css: NativeCssDeclarations): TextStyle = style(nativeStyle(base), css).textStyle

    internal fun style(base: NativeTextStyle, css: NativeCssDeclarations): NativeTextStyle {
        if (!css.affectsTypography) return base
        val started = if (stageTimings) System.nanoTime() else 0L
        val entry = typographyEntry(css)
        val result = if (entry.modifier.changes) {
            entry.inherited.getOrPut(base) { nativeStyle(entry.modifier.apply(base.textStyle, density.density)) }
        } else base
        if (started != 0L) resourceNanos += System.nanoTime() - started
        return result
    }

    internal fun readingStyle(base: TextStyle, css: NativeCssDeclarations): TextStyle {
        val started = if (stageTimings) System.nanoTime() else 0L
        val entry = typographyEntry(css)
        val readings = entry.readings ?: HashMap<TextStyle, TextStyle>().also { entry.readings = it }
        val result = readings.getOrPut(base) {
            val parent = nativeStyle(base)
            val inherited = if (entry.modifier.changes) entry.inherited.getOrPut(parent) {
                nativeStyle(entry.modifier.apply(base, density.density))
            }.textStyle else base
            rubyAnnotationStyle(base, inherited, css)
        }
        if (started != 0L) resourceNanos += System.nanoTime() - started
        return result
    }

    fun box(css: NativeCssDeclarations, style: TextStyle): BoxStyle {
        if (!css.affectsBox) return EMPTY_BOX
        val started = if (stageTimings) System.nanoTime() else 0L
        var entry = boxes[css]
        if (entry == null) {
            boxMisses++
            entry = BoxEntry(if (css.relativeBox) null else parseBoxStyle(css, 16f))
            boxes[css] = entry
        }
        val result = entry.fixed ?: run {
            val size = fontSizeSp(style)
            checkNotNull(entry.relative).getOrPut(size) {
                boxMisses++
                parseBoxStyle(css, size)
            }
        }
        if (started != 0L) resourceNanos += System.nanoTime() - started
        return result
    }

    fun profile(style: TextStyle): NativeTextProfile = nativeStyle(style).profile

    internal fun createProfile(style: TextStyle): NativeTextProfile {
        val started = if (stageTimings) System.nanoTime() else 0L
        profileMisses++
        val entry = NativeTextProfile(ruby.resources(style), style.background.takeIf { it.isSpecified }?.toArgb() ?: 0)
        profiles.add(entry)
        fontValidity.fontAdded()
        if (started != 0L) resourceNanos += System.nanoTime() - started
        return entry
    }

    fun layout(text: CharSequence, width: Int, style: TextStyle, paragraphProfile: NativeTextProfile? = null,
        lineHeight: NativeCssLineHeight? = null, intrinsic: Boolean = false): Layout {
        val started = if (stageTimings) System.nanoTime() else 0L
        return try {
            LookupProfiler.section(profilingId, "native.text.layout") {
                if (text !is Spanned) plainTextParagraphs++
                // Intrinsic cell flows retain advances only; their position and painting are
                // discarded. A normal origin permits Android's single-line measurement path.
                val alignment = if (intrinsic && direction == LayoutDirection.Ltr) Layout.Alignment.ALIGN_NORMAL else when (style.textAlign) {
                    TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
                    TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                    TextAlign.Right -> if (direction == LayoutDirection.Rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
                    TextAlign.Left -> if (direction == LayoutDirection.Rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                val paint = (paragraphProfile ?: profile(style)).paragraphPaint
                // Avoid measuring long paragraphs twice. One em per UTF-16 unit is the CJK
                // upper bound, but Latin text averages about half an em: the old budget made
                // short Latin paragraphs skip BoringLayout and pay for a StaticLayout. Android
                // still verifies the real advance before choosing the single line, so the
                // wider probe only costs one measure when a paragraph really wraps.
                if (direction == LayoutDirection.Ltr &&
                    text.length * paint.textSize <= width * SINGLE_LINE_PROBE_FACTOR) {
                    val metrics = BoringLayout.isBoring(text, paint, null)
                    if (metrics != null && metrics.width <= width.coerceAtLeast(1)) {
                        lineHeight?.chooseHeight(text, 0, text.length, 0, 0, metrics)
                        return@section BoringLayout.make(text, paint, width.coerceAtLeast(1), alignment,
                            1f, 0f, metrics, false).also { paragraphs++; singleLineParagraphs++ }
                    }
                }
                val layoutText = if (lineHeight == null) text else {
                    (text as? Spannable ?: SpannableString(text)).also {
                        it.setSpan(lineHeight, 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                }
                StaticLayout.Builder.obtain(layoutText, 0, text.length, paint, width.coerceAtLeast(1))
                    .setAlignment(alignment)
                    .setIncludePad(false)
                    .setTextDirection(if (direction == LayoutDirection.Rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .apply {
                        if (Build.VERSION.SDK_INT >= 33) setLineBreakConfig(checkNotNull(lineBreakConfig))
                    }
                    .setJustificationMode(if (style.textAlign == TextAlign.Justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
                    .build().also { paragraphs++ }
            }
        } finally { if (started != 0L) lineLayoutNanos += System.nanoTime() - started }
    }

    internal fun marker(text: String, style: TextStyle): NativeListMarkerPaint = marker(text, profile(style))

    private fun marker(text: String, profile: NativeTextProfile): NativeListMarkerPaint {
        listMarkers++
        return markers.getOrPut(profile) { HashMap() }.getOrPut(text) {
            LookupProfiler.section(profilingId, "native.list.marker.prepare") {
                val font = profile.resource
                val glyphs = font.font.shape(text, 0, text.length, if (Build.VERSION.SDK_INT < 31) text.toCharArray() else null)
                val commands = RubyGlyphBatchBuilder()
                glyphs.appendTo(commands, 0f, -font.baselineShiftPx, font.paint)
                NativeListMarkerPaint(text, ceil(glyphs.advancePx).toInt().coerceAtLeast(1),
                    font.paint.ascent() - font.baselineShiftPx, font.paint.descent() - font.baselineShiftPx,
                    RubyParagraphDrawing(commands.build()))
            }
        }
    }

    /**
     * CSS 2.1 10.8: half the line-height leading above the font's em box. Blink sizes an inline
     * box from the font-size, not the font's hhea ascent+descent, and a ruby annotation only
     * grows the line by the space it needs beyond that leading.
     */
    fun cssHalfLeading(style: TextStyle): Float {
        val fontSize = if (style.fontSize.isSp && style.fontSize.value > 0f) with(density) { style.fontSize.toPx() }
            else profile(style).resource.paint.textSize
        val lineHeight = if (style.lineHeight.isSp && style.lineHeight.value > 0f) with(density) { style.lineHeight.toPx() } else fontSize * 1.4f
        return (lineHeight - fontSize) / 2f
    }

    fun summary() = "backend=native-static-layout paragraphs=$paragraphs rubyPairs=$rubyPairs " +
        "css=$cssMatches elements=$elements tables=$tables fontProfiles=${profiles.size} " +
        "paragraphLineLayouts=$paragraphs singleLineLayouts=$singleLineParagraphs staticLayouts=${paragraphs - singleLineParagraphs} paragraphLineLayoutMs=${lineLayoutNanos / 1e6} " +
        "declarationsMs=${declarationsNanos / 1e6} resourceMs=${resourceNanos / 1e6} " +
        "paragraphFinishMs=${paragraphFinishNanos / 1e6} paragraphGeometryMs=${paragraphGeometryNanos / 1e6} " +
        "decorationMs=${decorationNanos / 1e6} rubyPlacementMs=${rubyPlacementNanos / 1e6} " +
        "ancestorMs=${ancestorNanos / 1e6} rubyLowerMs=${rubyLowerNanos / 1e6} tableMs=${tableNanos / 1e6} " +
        "listMarkers=$listMarkers minimumWidthReads=0 maximumWidthReads=0"
}

/** Snapshot font changes and newly registered profiles invalidate a worker's shared result. */
internal class NativeFontValidity(hasStaleFont: () -> Boolean) {
    private var revision by mutableIntStateOf(0)
    private val result = derivedStateOf { revision; hasStaleFont() }
    val stale: Boolean get() = result.value
    fun fontAdded() { revision++ }
}

/** An outside marker is a shaped paint item, not another wrapping paragraph. */
internal class NativeListMarkerPaint(val text: String, val width: Int, val ascent: Float, val descent: Float,
    val drawing: RubyParagraphDrawing)

internal class NativeListMarker(override val x: Int, val baseline: Float,
    private val marker: NativeListMarkerPaint) : NativeGlossaryItem {
    val text get() = marker.text
    override val y = floor(baseline + marker.ascent).toInt()
    override val width get() = marker.width
    override val height = ceil(baseline + marker.descent).toInt() - y
    override fun moved(dx: Int, dy: Int) = NativeListMarker(x + dx, baseline + dy, marker)
    override fun draw(canvas: Canvas, paint: Paint) = marker.drawing.draw(canvas, x.toFloat(), baseline)
}

public class NativeTextProfile(val resource: RubyTextStyle, private val background: Int) {
    private val color = resource.paint.color
    private val underline = resource.paint.isUnderlineText
    private val strikeThrough = resource.paint.isStrikeThruText
    private val paintBackground = resource.paint.bgColor
    // Layout retains its base paint and uses a separate work paint for spans/drawing. Never
    // repurpose this as a mutable scratch paint while any of its layouts remain alive.
    internal val paragraphPaint: TextPaint by lazy { TextPaint(resource.paint) }

    fun apply(paint: TextPaint) {
        paint.set(resource.paint)
        paint.bgColor = background
        paint.baselineShift = -resource.baselineShiftPx.roundToInt()
    }

    fun applyPaint(paint: TextPaint) {
        paint.color = color
        paint.bgColor = background
        paint.isUnderlineText = underline
        paint.isStrikeThruText = strikeThrough
        val shadow = resource.style.shadow
        if (shadow == null) paint.clearShadowLayer()
        else paint.setShadowLayer(shadow.blurRadius, shadow.offset.x, shadow.offset.y, shadow.color.toArgb())
    }

    fun spanFor(paragraph: NativeTextProfile): CharacterStyle? {
        if (resource.font !== paragraph.resource.font || resource.baselineShiftPx != 0f) {
            return NativeStyleSpan(this)
        }
        if (this === paragraph && background == paintBackground) return null
        // The layout already has the paragraph paint. Background is deliberately compared
        // against that paint, not the profile: CSS backgrounds are applied by spans.
        if (color == paragraph.color && background == paragraph.paintBackground &&
            underline == paragraph.underline && strikeThrough == paragraph.strikeThrough &&
            resource.style.shadow == paragraph.resource.style.shadow) return null
        return NativePaintStyleSpan(this)
    }
}

public class NativePaintStyleSpan(private val profile: NativeTextProfile) : CharacterStyle() {
    override fun updateDrawState(textPaint: TextPaint) = profile.applyPaint(textPaint)
}

public class NativeStyleSpan(val profile: NativeTextProfile) : MetricAffectingSpan() {
    override fun updateMeasureState(textPaint: TextPaint) = profile.apply(textPaint)
    override fun updateDrawState(textPaint: TextPaint) = profile.apply(textPaint)
}

/**
 * Reserves an inline replaced element's box in the paragraph. The view draws the bitmap at the
 * position computed from this span, so the placeholder contributes only metrics.
 *
 * [shiftPx] is CSS `vertical-align`: how far the box's bottom margin edge sits below the baseline.
 * Zero is `baseline`; `text-bottom` passes the font descent. The line box only grows when the box
 * extends above the ascent or below the descent.
 */
public class NativeInlineImageSpan(private val widthPx: Int, private val heightPx: Int,
    private val shiftPx: Int = 0) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val top = shiftPx - heightPx
            if (top < fm.ascent) {
                fm.ascent = top
                fm.top = minOf(fm.top, top)
            }
            if (shiftPx > fm.descent) {
                fm.descent = shiftPx
                fm.bottom = maxOf(fm.bottom, shiftPx)
            }
        }
        return widthPx
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
        bottom: Int, paint: Paint) {
        // NativeGlossaryImage draws the bitmap.
    }
}

/** Blink ListMarker: a 0.66em baseline-aligned triangle followed by 0.4em of space. */
internal class NativeDisclosureMarkerSpan(fontSize: Float, open: Boolean, rtl: Boolean) : ReplacementSpan() {
    private val advance = ceil(fontSize * 1.06f).toInt()
    private val shape = Path().apply {
        val size = fontSize * 0.66f
        when {
            open -> { moveTo(0f, -size * 0.93f); lineTo(size * 0.5f, -size * 0.07f); lineTo(size, -size * 0.93f) }
            rtl -> { moveTo(size, -size); lineTo(size * 0.14f, -size * 0.5f); lineTo(size, 0f) }
            else -> { moveTo(0f, -size); lineTo(size * 0.86f, -size * 0.5f); lineTo(0f, 0f) }
        }
        close()
        if (rtl) offset(advance - size, 0f)
    }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = advance
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int,
        bottom: Int, paint: Paint) {
        val saved = canvas.save()
        canvas.translate(x, y.toFloat())
        canvas.drawPath(shape, paint)
        canvas.restoreToCount(saved)
    }
}

/** Metrics participate in line breaking; painting is just the already prepared native glyphs. */
public class NativeRubySpan(val base: String, metrics: RubyMetrics, val start: Int, val end: Int) : ReplacementSpan() {
    var metrics = metrics
        private set

    internal fun reserveAnnotations(before: Float, after: Float, fontSize: Float, rounding: Float): Float {
        val original = metrics
        val baseWidth = original.baseLayout.advancePx
        val excess = (original.readingLayout.advancePx - baseWidth).coerceAtLeast(0f)
        val limit = minOf(excess / 2f, fontSize / 2f)
        val left = minOf(limit, before / 2f)
        val right = minOf(limit, after / 2f)
        val advance = baseWidth + excess - left - right + rounding
        val width = advance.roundToInt().coerceAtLeast(1)
        val offset = (right - left) / 2f
        if (width == original.widthPx && offset == original.inlineOffsetPx) return advance - width
        metrics = RubyMetrics(width, original.heightPx,
            original.readingOffsetPx, original.baseOffsetPx, original.baseText, original.readingText,
            original.baseLayout, original.readingLayout, original.readingStyle, offset)
        return advance - width
    }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        // Annotation height is accounted for by the CSS line box, not this width placeholder.
        return metrics.widthPx
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        // NativeTextFlow draws the retained glyphs together, after the body text.
    }
}

public class NativeLookupLink(val target: String)
public class NativeNoLookup

/** Generated inline-block text owns an advance; CSS transforms move paint, not its line box. */
internal class NativeGeneratedTextSpan(prepared: PreparedRubyText, private val advance: Int,
    private val inset: Int, private val shiftY: Float) : ReplacementSpan() {
    private val drawing = RubyGlyphBatchBuilder().let { builder ->
        prepared.appendTo(builder, inset.toFloat(), -prepared.firstBaseline)
        RubyParagraphDrawing(builder.build())
    }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
        fm: Paint.FontMetricsInt?): Int = advance
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = drawing.draw(canvas, x, y + shiftY)
}

public class NativeShiftedRun(val start: Int, val end: Int, font: RubyTextStyle) {
    val shift = font.baselineShiftPx.roundToInt()
    val above: Int
    val below: Int
    val fontAscent: Int
    val fontDescent: Int

    init {
        val metrics = font.font.metricsInt
        fontAscent = -metrics.ascent
        fontDescent = metrics.descent
        val leading = floor((font.lineHeightPx - (metrics.descent - metrics.ascent)) / 2f).toInt()
        above = -metrics.ascent + leading + shift
        below = ceil(font.lineHeightPx).toInt() - above
    }
}

internal fun inlineRangeOverlaps(start: Int, end: Int, queryStart: Int, queryEnd: Int): Boolean =
    start <= queryEnd && end >= queryStart &&
        (start == end || queryStart == queryEnd || start != queryEnd && end != queryStart)

/** Appended inline ranges are disjoint and ordered; skip preceding runs without scanning them. */
internal inline fun <T> firstRangeEndingAtOrAfter(runs: List<T>, offset: Int, endOf: (T) -> Int): Int {
    var low = 0
    var high = runs.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (endOf(runs[middle]) < offset) low = middle + 1 else high = middle
    }
    return low
}

/**
 * CSS 2.1 10.8: line boxes advance by the computed line-height, and Blink puts line k's top at
 * `k * line-height` in fractional device px. Android lays lines out on whole pixels, and the
 * layout's own `spacingAdd` cannot follow a fractional line-height - it is the same adjustment
 * on every line, and it is dropped on the last one. Case 66's body came out with 52px lines
 * where the reference advances 51.35: the eighth line sat 4 device px low.
 *
 * So the span owns the line box: line k is `round((k+1) * line-height) - round(k * line-height)`
 * tall, which never puts a line top further than half a pixel from the reference's.
 *
 * Shifted inline boxes and replaced elements may enlarge the line; ordinary ink overflow does
 * not. Baseline/negative-leading compatibility is tracked separately in BLINK_METHOD.md.
 */
public class NativeCssLineHeight(
    private val lineHeightPx: Float,
    private val rubyExtra: Int = 0,
    private val baseAscent: Int = 0,
    private val baseDescent: Int = 0,
    /** The document's first line box, which the reference crops at the base text: a
     *  `vertical-align: super` run above it must not push the line down. Later line boxes grow
     *  upward, as CSS lays them out. */
    private val pinFirstLine: Boolean = false,
    private val rubyRuns: List<NativeRubySpan> = emptyList(),
    private val shiftedRuns: List<NativeShiftedRun> = emptyList(),
    private val imageOffsets: List<Int> = emptyList(),
    private val lineBoxOrigin: Boolean = false,
    private val cropFirstRubyLine: Boolean = false,
) : LineHeightSpan {
    private var lines = 0
    private var lastTop = -1
    private var ascent = 0
    private var height = 0

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineTop: Int, fm: Paint.FontMetricsInt) {
        if (lineTop != lastTop || lineTop == 0) {
            if (lineTop == 0) lines = 0
            val extra = if (rubyExtra > 0 && !(cropFirstRubyLine && lines == 0) &&
                hasRuby(start, end, text.length)) rubyExtra else 0
            val grid = Math.round((lines + 1) * lineHeightPx) - Math.round(lines * lineHeightPx)
            // CSS 2.1 10.8.1 shifts a `vertical-align: super` box and lets it overhang the line:
            // the base text stays on the line's grid and the reading pokes out above it. Only
            // the document's first line is measured from the base text (the popup crops there);
            // case 33's ⓪ otherwise pulled its whole line 13 device px down.
            val pin = pinFirstLine && lines == 0 && baseDescent > 0
            var shiftUp = 0
            var shiftDown = 0
            var alignedTop = 0
            var alignedBottom = 0
            var top = baseAscent
            var bottom = baseDescent
            for (index in firstRangeEndingAtOrAfter(shiftedRuns, start) { it.end } until shiftedRuns.size) {
                val run = shiftedRuns[index]
                if (run.start > end) break
                if (run.start >= text.length || !inlineRangeOverlaps(run.start, minOf(run.end, text.length), start, end)) continue
                shiftUp = maxOf(shiftUp, run.shift)
                shiftDown = maxOf(shiftDown, -run.shift)
                if (run.shift != 0) {
                    alignedTop = maxOf(alignedTop, run.above)
                    alignedBottom = maxOf(alignedBottom, run.below)
                }
                top = maxOf(top, run.fontAscent)
                bottom = maxOf(bottom, run.fontDescent)
            }
            // Strip Android's raw baseline-shift expansion, then union the shifted CSS line
            // boxes below. Font ink overflow alone must not enlarge an ordinary line.
            val hasImage = imageOffsets.any { inlineRangeOverlaps(it, it + 1, start, end) }
            if (hasImage) {
                top = maxOf(top, -fm.ascent - shiftUp)
                bottom = maxOf(bottom, fm.descent - shiftDown)
            }
            val natural = top + bottom
            // A replaced element may need more room than the line-height: the line box then
            // takes the element's own box, as CSS does.
            height = if (extra > 0 || hasImage)
                maxOf(natural, grid + extra) else grid
            // Table cells start at the CSS line-box origin and retain negative half-leading.
            // Outer flows preserve the existing font-box crop contract.
            ascent = top + if (height < natural && lineBoxOrigin) negativeHalfLeading(height, natural)
                else (height - natural).coerceAtLeast(0)
            val descent = maxOf(height - ascent, alignedBottom)
            if (!pin) ascent = maxOf(ascent, alignedTop)
            height = ascent + descent
            lines++
        }
        lastTop = lineTop
        fm.ascent = -ascent
        fm.descent = height - ascent
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
    }

    private fun hasRuby(start: Int, end: Int, textLength: Int): Boolean {
        for (index in firstRangeEndingAtOrAfter(rubyRuns, start) { it.end } until rubyRuns.size) {
            val run = rubyRuns[index]
            if (run.start > end) break
            if (run.start < textLength && inlineRangeOverlaps(run.start, minOf(run.end, textLength), start, end)) return true
        }
        return false
    }
}

public class NativeTextFlow private constructor(
    val layout: Layout,
    override val x: Int,
    override val y: Int,
    val interactive: Boolean,
    private val rubyDrawing: RubyParagraphDrawing? = null,
) : NativeGlossaryItem {
    private class RubyHit(val run: NativeRubySpan, val left: Float, val baseline: Float) {
        private val metrics = run.metrics
        private val base = metrics.baseLayout
        private val reading = metrics.readingLayout
        private val readingBaseline = baseline - base.firstBaseline - metrics.heightPx +
            metrics.readingOffsetPx + reading.firstBaseline
        private val readingLeft = left + metrics.inlineOffsetPx + (metrics.widthPx - reading.advancePx) / 2f
        private val readingTop = readingBaseline - (reading.emAscentPx ?: reading.ascentPx)
        private val readingBottom = readingBaseline + (reading.emDescentPx ?: reading.descentPx)
        private val baseTop = baseline - (base.emAscentPx ?: base.ascentPx)
        private val baseBottom = baseline + (base.emDescentPx ?: base.descentPx)

        fun isAnnotation(x: Float, y: Float): Boolean = x >= readingLeft && x < readingLeft + reading.advancePx &&
            y >= readingTop && y < readingBottom

        fun baseOffset(x: Float, y: Float): Int? {
            if (x < left || x >= left + metrics.widthPx || y < baseTop || y >= baseBottom) return null
            return run.start + base.characterOffset(x - left - metrics.inlineOffsetPx - (metrics.widthPx - base.advancePx) / 2f)
        }
    }

    private var rubyHits: List<RubyHit> = emptyList()
    override val width: Int get() = layout.width
    override val height: Int get() = layout.height
    override fun moved(dx: Int, dy: Int) = NativeTextFlow(layout, x + dx, y + dy, interactive, rubyDrawing)
        .also { it.rubyHits = rubyHits }
    fun lookupAt(px: Float, py: Float): String? {
        if (!interactive || px < x || py < y || px >= x + layout.width || py >= y + layout.height) return null
        val localX = px - x
        val localY = py - y
        val line = layout.getLineForVertical(localY.toInt())
        if (localX < layout.getLineLeft(line) || localX >= layout.getLineRight(line)) return null
        val text = layout.text
        val spanned = text as? Spanned
        // Replacement spans have only edge carets. Hit their base directly and reject only
        // annotations, including annotations overhanging the preceding line. Selection-path
        // bounds can include unrelated text on the line and must not be used as ruby bounds.
        if (spanned != null) {
            var baseOffset: Int? = null
            for (hit in rubyHits) {
                if (hit.isAnnotation(localX, localY)) return null
                hit.baseOffset(localX, localY)?.let { baseOffset = it }
            }
            baseOffset?.let { offset ->
                if (spanned.getSpans(offset, offset + 1, NativeNoLookup::class.java).isNotEmpty()) return null
                return spanned.getSpans(offset, offset + 1, NativeLookupLink::class.java).firstOrNull()?.target
                    ?: lookupWordAt(text.toString(), offset)
            }
        }
        var offset = layout.getOffsetForHorizontal(line, localX).coerceIn(0, (text.length - 1).coerceAtLeast(0))
        if (offset > layout.getLineStart(line)) {
            val previous = Character.offsetByCodePoints(text, offset, -1)
            val a = layout.getPrimaryHorizontal(previous)
            val b = layout.getPrimaryHorizontal(offset)
            if (localX >= minOf(a, b) && localX < maxOf(a, b)) offset = previous
        }
        if (spanned != null) {
            if (spanned.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeNoLookup::class.java).isNotEmpty()) return null
            if (spanned.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeRubySpan::class.java).isNotEmpty()) return null
            spanned.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeLookupLink::class.java)
                .firstOrNull()?.let { return it.target }
        }
        return lookupWordAt(text.toString(), offset)
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        val checkpoint = canvas.save()
        canvas.translate(x.toFloat(), y.toFloat())
        layout.draw(canvas)
        rubyDrawing?.draw(canvas, 0f, 0f)
        canvas.restoreToCount(checkpoint)
    }

    companion object {
        fun prepare(layout: Layout, x: Int, y: Int, interactive: Boolean, rubyRuns: List<NativeRubySpan>,
            prepareRubyDrawing: Boolean = true): NativeTextFlow {
            // Intrinsic table flows contribute widths only; their paint batches are discarded.
            if (!prepareRubyDrawing) return NativeTextFlow(layout, x, y, interactive)
            val text = layout.text
            if (rubyRuns.isEmpty()) return NativeTextFlow(layout, x, y, interactive)
            val builder = RubyGlyphBatchBuilder()
            val hits = if (interactive) ArrayList<RubyHit>(rubyRuns.size) else null
            // getPrimaryHorizontal remeasures a line prefix for EVERY column. API 34 exposes
            // TextLine's linear batch measurement; reuse it for all ruby columns in this flow.
            val bounds = if (Build.VERSION.SDK_INT >= 34 && rubyRuns.size > 1) {
                FloatArray(text.length * 4).also { layout.fillCharacterBounds(0, text.length, it, 0) }
            } else null
            for (run in rubyRuns) {
                val start = run.start
                if (start >= text.length) continue
                // Base box top = baseline - (line-height + ascent - descent)/2 (CSS 2.1 10.8.1);
                // the annotation box sits directly above it (CSS Ruby 1 §3.6).
                val line = layout.getLineForOffset(start)
                val top = layout.getLineBaseline(line) -
                    run.metrics.heightPx - run.metrics.baseLayout.firstBaseline
                // getSelectionPath bounds cover the whole line, not the span, so two ruby columns
                // on one line would both be drawn at the line's left edge. The caret position at
                // the span's start is its leading edge (right edge in RTL, where advance runs left).
                val left = if (bounds != null) bounds[start * 4] else {
                    val primary = layout.getPrimaryHorizontal(start)
                    if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) primary - run.metrics.widthPx else primary
                }
                RubyParagraphDrawing.appendColumn(builder, run.metrics, left, top)
                hits?.add(RubyHit(run, left, layout.getLineBaseline(line).toFloat()))
            }
            return NativeTextFlow(layout, x, y, interactive, RubyParagraphDrawing(builder.build()))
                .also { it.rubyHits = hits ?: emptyList() }
        }
    }
}
