package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.compose.ui.graphics.isSpecified
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
) {
    val ruby = RubyTextRenderer(density, direction, resolver)
    private val lineBreakConfig = if (Build.VERSION.SDK_INT >= 33) LineBreakConfig.Builder()
        .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NORMAL)
        .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE).build() else null
    // Hash-consed declaration maps. A merged declaration map is allocated per element, but only a
    // handful of DISTINCT ones exist, so structurally equal maps are interned to one instance.
    // That turns every downstream lookup into an identity comparison (IdentityHashMap uses
    // System.identityHashCode) instead of re-hashing the whole map, and it removes one map
    // allocation per element - which is what feeds the allocation rate that GC charges for.
    private val consed = HashMap<Map<String, String>, Map<String, String>>()
    private class TypographyEntry(val modifier: GlossaryTypography) {
        val inherited = HashMap<TextStyle, TextStyle>()
    }
    private val typography = IdentityHashMap<Map<String, String>, TypographyEntry>()
    private val boxes = IdentityHashMap<Map<String, String>, HashMap<Float, BoxStyle>>()
    private val profiles = HashMap<TextStyle, NativeTextProfile>()
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
        set(value) { field = value; ruby.profilingId = value }
    var paragraphs = 0
    var rubyPairs = 0
    var cssMatches = 0
    var elements = 0
    var tables = 0
    var lineLayoutNanos = 0L
    val hasStaleFonts: Boolean get() = profiles.values.any { it.resource.font.isStale }

    /**
     * Interns a merged declaration map so structurally equal maps share one instance. The map is
     * used as an IDENTITY key by [style] and [box] afterwards, so lookups never hash its contents.
     */
    fun intern(css: Map<String, String>): Map<String, String> {
        if (css.isEmpty()) return css
        val hit = consed[css]
        if (hit != null) return hit
        consed[css] = css
        return css
    }

    /** [css] must be interned (see [intern]); lookups are then identity-based. */
    fun style(base: TextStyle, css: Map<String, String>): TextStyle {
        val started = System.nanoTime()
        var entry = typography[css]
        if (entry == null) {
            typographyMisses++
            entry = TypographyEntry(GlossaryTypography.read(css))
            typography[css] = entry
        }
        val result = if (entry.modifier.changes) {
            entry.inherited.getOrPut(base) { entry.modifier.apply(base, density.density) }
        } else base
        resourceNanos += System.nanoTime() - started
        return result
    }

    /** [css] must be interned (see [intern]); lookups are then identity-based. */
    fun box(css: Map<String, String>, style: TextStyle): BoxStyle {
        val started = System.nanoTime()
        val size = fontSizeSp(style)
        var bySize = boxes[css]
        if (bySize == null) {
            boxMisses++
            bySize = HashMap()
            boxes[css] = bySize
        }
        var entry = bySize[size]
        if (entry == null) {
            boxMisses++
            entry = parseBoxStyle(css, size)
            bySize[size] = entry
        }
        resourceNanos += System.nanoTime() - started
        return entry
    }

    fun profile(style: TextStyle): NativeTextProfile {
        val started = System.nanoTime()
        var entry = profiles[style]
        if (entry == null) {
            profileMisses++
            entry = NativeTextProfile(ruby.resources(style), style.background.takeIf { it.isSpecified }?.toArgb() ?: 0)
            profiles[style] = entry
        }
        resourceNanos += System.nanoTime() - started
        return entry
    }

    fun layout(text: CharSequence, width: Int, style: TextStyle, paragraphProfile: NativeTextProfile? = null): StaticLayout {
        val started = System.nanoTime()
        return try {
            LookupProfiler.section(profilingId, "native.text.layout") {
                val alignment = when (style.textAlign) {
                    TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
                    TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                    TextAlign.Right -> if (direction == LayoutDirection.Rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
                    TextAlign.Left -> if (direction == LayoutDirection.Rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                val paint = TextPaint((paragraphProfile ?: profile(style)).resource.paint)
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
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
        } finally { lineLayoutNanos += System.nanoTime() - started }
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
        "paragraphLineLayouts=$paragraphs paragraphLineLayoutMs=${lineLayoutNanos / 1e6} " +
        "declarationsMs=${declarationsNanos / 1e6} resourceMs=${resourceNanos / 1e6} " +
        "minimumWidthReads=0 maximumWidthReads=0"
}

public class NativeTextProfile(val resource: RubyTextStyle, private val background: Int) {
    fun apply(paint: TextPaint) {
        paint.set(resource.paint)
        paint.bgColor = background
        paint.baselineShift = -resource.baselineShiftPx.roundToInt()
    }

    fun applyPaint(paint: TextPaint) {
        paint.color = resource.paint.color
        paint.bgColor = background
        paint.isUnderlineText = resource.paint.isUnderlineText
        paint.isStrikeThruText = resource.paint.isStrikeThruText
        val shadow = resource.style.shadow
        if (shadow == null) paint.clearShadowLayer()
        else paint.setShadowLayer(shadow.blurRadius, shadow.offset.x, shadow.offset.y, shadow.color.toArgb())
    }

    fun spanFor(paragraph: NativeTextProfile): CharacterStyle =
        if (resource.font === paragraph.resource.font && resource.baselineShiftPx == 0f) {
            NativePaintStyleSpan(this)
        } else {
            NativeStyleSpan(this)
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
public class NativeRubySpan(val base: String, val metrics: RubyMetrics) : ReplacementSpan() {
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
    private val hasShiftedText: Boolean = false,
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
            val spanned = text as? Spanned
            val extra = if (!(cropFirstRubyLine && lines == 0) && spanned != null &&
                spanned.getSpans(start, end, NativeRubySpan::class.java).isNotEmpty()) rubyExtra else 0
            val grid = Math.round((lines + 1) * lineHeightPx) - Math.round(lines * lineHeightPx)
            // CSS 2.1 10.8.1 shifts a `vertical-align: super` box and lets it overhang the line:
            // the base text stays on the line's grid and the reading pokes out above it. Only
            // the document's first line is measured from the base text (the popup crops there);
            // case 33's ⓪ otherwise pulled its whole line 13 device px down.
            val pin = pinFirstLine && lines == 0 && baseDescent > 0
            val runs = if (hasShiftedText) spanned?.getSpans(start, end, NativeStyleSpan::class.java) else null
            var shiftUp = 0
            var shiftDown = 0
            var alignedTop = 0
            var alignedBottom = 0
            if (runs != null) for (run in runs) {
                val font = run.profile.resource
                val shift = font.baselineShiftPx.roundToInt()
                if (shift == 0) continue
                shiftUp = maxOf(shiftUp, shift)
                shiftDown = maxOf(shiftDown, -shift)
                val metrics = font.paint.fontMetricsInt
                val leading = floor((font.lineHeightPx - (metrics.descent - metrics.ascent)) / 2f).toInt()
                val above = -metrics.ascent + leading + shift
                alignedTop = maxOf(alignedTop, above)
                alignedBottom = maxOf(alignedBottom, ceil(font.lineHeightPx).toInt() - above)
            }
            // Strip Android's raw baseline-shift expansion, then union the shifted CSS line
            // boxes below. Font ink overflow alone must not enlarge an ordinary line.
            val top = maxOf(baseAscent, -fm.ascent - shiftUp)
            val bottom = maxOf(baseDescent, fm.descent - shiftDown)
            val natural = top + bottom
            // A replaced element may need more room than the line-height: the line box then
            // takes the element's own box, as CSS does.
            height = if (extra > 0 || hasReplaced(spanned, start, end)) maxOf(natural, grid + extra) else grid
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

    private fun hasReplaced(spanned: Spanned?, start: Int, end: Int): Boolean =
        spanned != null && spanned.getSpans(start, end, NativeInlineImageSpan::class.java).isNotEmpty()
}

public class NativeTextFlow(
    val layout: StaticLayout,
    override val x: Int,
    override val y: Int,
    val interactive: Boolean,
    private val rubyDrawing: RubyParagraphDrawing? = null,
) : NativeGlossaryItem {
    override val width: Int get() = layout.width
    override val height: Int get() = layout.height
    override fun moved(dx: Int, dy: Int) = NativeTextFlow(layout, x + dx, y + dy, interactive, rubyDrawing)
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
            for (ruby in spanned.getSpans(0, text.length, NativeRubySpan::class.java)) {
                val start = spanned.getSpanStart(ruby)
                val rubyLine = layout.getLineForOffset(start)
                val metrics = ruby.metrics
                val primary = layout.getPrimaryHorizontal(start)
                val left = if (layout.getParagraphDirection(rubyLine) == Layout.DIR_RIGHT_TO_LEFT) primary - metrics.widthPx else primary
                val baseline = layout.getLineBaseline(rubyLine).toFloat()
                val reading = metrics.readingLayout
                val readingBaseline = baseline - metrics.baseLayout.firstBaseline - metrics.heightPx +
                    metrics.readingOffsetPx + reading.firstBaseline
                val readingLeft = left + (metrics.widthPx - reading.advancePx) / 2f
                if (localX >= readingLeft && localX < readingLeft + reading.advancePx &&
                    localY >= readingBaseline - (reading.emAscentPx ?: reading.ascentPx) &&
                    localY < readingBaseline + (reading.emDescentPx ?: reading.descentPx)) return null
                val base = metrics.baseLayout
                if (localX >= left && localX < left + metrics.widthPx &&
                    localY >= baseline - (base.emAscentPx ?: base.ascentPx) &&
                    localY < baseline + (base.emDescentPx ?: base.descentPx)) {
                    baseOffset = start + base.characterOffset(localX - left - (metrics.widthPx - base.advancePx) / 2f)
                }
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
        fun prepare(layout: StaticLayout, x: Int, y: Int, interactive: Boolean,
            prepareRubyDrawing: Boolean = true): NativeTextFlow {
            // Intrinsic table flows contribute widths only; their paint batches are discarded.
            if (!prepareRubyDrawing) return NativeTextFlow(layout, x, y, interactive)
            val text = layout.text as? Spanned
            val spans = text?.getSpans(0, text.length, NativeRubySpan::class.java)
            if (text == null || spans.isNullOrEmpty()) return NativeTextFlow(layout, x, y, interactive)
            val builder = RubyGlyphBatchBuilder()
            // getPrimaryHorizontal remeasures a line prefix for EVERY column. API 34 exposes
            // TextLine's linear batch measurement; reuse it for all ruby columns in this flow.
            val bounds = if (Build.VERSION.SDK_INT >= 34 && spans.size > 1) {
                FloatArray(text.length * 4).also { layout.fillCharacterBounds(0, text.length, it, 0) }
            } else null
            for (span in spans) {
                val start = text.getSpanStart(span)
                // Base box top = baseline - (line-height + ascent - descent)/2 (CSS 2.1 10.8.1);
                // the annotation box sits directly above it (CSS Ruby 1 §3.6).
                val line = layout.getLineForOffset(start)
                val top = layout.getLineBaseline(line) -
                    span.metrics.heightPx - span.metrics.baseLayout.firstBaseline
                // getSelectionPath bounds cover the whole line, not the span, so two ruby columns
                // on one line would both be drawn at the line's left edge. The caret position at
                // the span's start is its leading edge (right edge in RTL, where advance runs left).
                val left = if (bounds != null) bounds[start * 4] else {
                    val primary = layout.getPrimaryHorizontal(start)
                    if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) primary - span.metrics.widthPx else primary
                }
                RubyParagraphDrawing.appendColumn(builder, span.metrics, left, top)
            }
            return NativeTextFlow(layout, x, y, interactive, RubyParagraphDrawing(builder.build()))
        }
    }
}
