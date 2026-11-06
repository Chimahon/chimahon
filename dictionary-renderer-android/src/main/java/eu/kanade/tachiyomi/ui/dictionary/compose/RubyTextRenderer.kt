package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import chimahon.diagnostics.LookupProfiler
import java.util.IdentityHashMap
import kotlin.math.ceil

/** UTF-16 ranges share the full shaping context instead of owning substring copies. */
public data class RubyTextRun(val start: Int, val end: Int, val style: TextStyle)

/** Normalized typo ascent/descent of a shaped run, in pixels. */
public data class RubyEmHeight(val ascentPx: Float, val descentPx: Float)

/** Native font/paint resources belong to a worker, regardless of which words it renders. */
public class RubyTextRenderer(
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
) {
    var profilingId: Long = 0
    private val fonts = HashMap<SpanStyle, RubyFontResources>()
    private val styles = HashMap<TextStyle, RubyTextStyle>()
    // The same style instances recur for every ruby pair; identity skips hashing their spans.
    private val identityStyles = IdentityHashMap<TextStyle, RubyTextStyle>()
    val fontProfileCount: Int get() = fonts.size
    var shapedTextRuns = 0
        private set

    fun resources(style: TextStyle): RubyTextStyle {
        identityStyles[style]?.takeUnless { it.font.isStale }?.let { return it }
        styles[style]?.takeUnless { it.font.isStale }?.let {
            identityStyles[style] = it
            return it
        }
        val resolved = resolveDefaults(style, direction)
        // Paint and line leading cannot change glyph selection or advances.
        val key = resolved.toSpanStyle().copy(color = Color.Unspecified, background = Color.Unspecified,
            textDecoration = null, shadow = null, baselineShift = null)
        val font = fonts[key]?.takeUnless { it.isStale } ?: LookupProfiler.section(profilingId, "ruby.font.prepare") {
            RubyFontResources(resolved, density, resolver, direction == LayoutDirection.Rtl)
        }.also { fonts[key] = it }
        return LookupProfiler.section(profilingId, "ruby.paint.prepare") { RubyTextStyle(style, font, density) }
            .also {
                styles[style] = it
                identityStyles[style] = it
            }
    }

    fun lineBox(style: TextStyle): LineBoxMetrics {
        val resource = resources(style)
        val (ascent, descent) = resource.font.strut(profilingId)
        val leading = ((resource.lineHeightPx - ascent - descent) / 2f).coerceAtLeast(0f)
        return LineBoxMetrics(ascent, descent, leading, ascent + leading)
    }

    fun prepare(text: String, style: TextStyle, runs: List<RubyTextRun> = emptyList(),
        resource: RubyTextStyle = resources(style)): PreparedRubyText {
        val pieces = ArrayList<RubyTextPiece>(maxOf(1, runs.size))
        var advance = 0f
        var ascent = 0f
        var descent = 0f
        var emAscent: Float? = null
        var emDescent: Float? = null
        var requestedHeight = resource.lineHeightPx
        val chars = if (Build.VERSION.SDK_INT < 31) text.toCharArray() else null
        fun append(start: Int, end: Int, runStyle: TextStyle) {
            if (start == end) return
            val runResource = if (runStyle === style) resource else resources(runStyle)
            requestedHeight = maxOf(requestedHeight, runResource.lineHeightPx)
            val glyphs = LookupProfiler.section(profilingId, "ruby.glyphs.shape") {
                runResource.font.shape(text, start, end - start, chars)
            }
            shapedTextRuns++
            pieces.add(RubyTextPiece(glyphs, runResource, advance, end - start))
            advance += glyphs.advancePx
            ascent = maxOf(ascent, glyphs.ascentPx + runResource.baselineShiftPx)
            descent = maxOf(descent, glyphs.descentPx - runResource.baselineShiftPx)
            glyphs.emHeight?.let {
                emAscent = maxOf(emAscent ?: 0f, it.ascentPx)
                emDescent = maxOf(emDescent ?: 0f, it.descentPx)
            }
        }
        if (runs.isEmpty()) append(0, text.length, style) else runs.forEach { append(it.start, it.end, it.style) }
        val halfLeading = ((requestedHeight - ascent - descent) / 2f).coerceAtLeast(0f)
        return PreparedRubyText(text, style, pieces, advance, ascent, descent, halfLeading,
            requestedHeight.takeIf { it > 0f } ?: (ascent + descent), emAscent, emDescent, resource.paint.textSize)
    }
}

public class RubyFontResources(private val textStyle: TextStyle, density: Density, private val fontResolver: FontFamily.Resolver,
    private val rtl: Boolean) {
    companion object {
        /** The skew a browser applies to synthesise an italic face: 0.25. */
        private const val SYNTHETIC_ITALIC_SKEW = -0.25f
        /** Text size for the one-off glyph-bounds probe that tells a real italic face from a flag. */
        private const val PROBE_TEXT_SIZE = 100f
    }
    private val typefaceState: State<Typeface> = fontResolver.resolveAsTypeface(
        textStyle.fontFamily, textStyle.fontWeight ?: FontWeight.Normal, textStyle.fontStyle ?: FontStyle.Normal,
        textStyle.fontSynthesis ?: FontSynthesis.All)
    private val resolved: Typeface = typefaceState.value
    private val regularTypeface: Typeface = if (textStyle.fontStyle == FontStyle.Italic) {
        fontResolver.resolveAsTypeface(textStyle.fontFamily, textStyle.fontWeight ?: FontWeight.Normal,
            FontStyle.Normal, textStyle.fontSynthesis ?: FontSynthesis.All).value
    } else resolved
    val isStale: Boolean get() = typefaceState.value != resolved
    /**
     * CSS synthesises a bold face when the family has none (2.1 15.5). Compose's resolver hands
     * back the regular face unchanged for a family that declares a single typeface, so the
     * requested weight was silently dropped and bold gloss text rendered at regular weight: the
     * reference's bold headword carried 1127 ink pixels where the native's carried 811.
     */
    private val syntheticBold: Boolean =
        !resolved.isBold && (textStyle.fontWeight?.weight ?: 400) >= 600
    /**
     * The same synthesis for italic: the staged face has no italic, and the browser skews it by
     * 0.25. Measured against the reference, the skewed 'l' of a gloss shifted 6px over 28 rows
     * while the upright one shifted -1, so the skew is 0.25 exactly.
     *
     * Android's resolver returns a typeface carrying the ITALIC flag, and Android draws that
     * UPRIGHT - the skew has to come from the paint. A family that really has an italic face
     * returns different glyph outlines, and there the browser uses that face instead of skewing.
     * So the regular face is used and the paint carries the skew when the outlines match, and the
     * real face is used as-is when they do not. Either way the glyphs end up slanted.
     */
    private val syntheticItalic: Boolean =
        textStyle.fontStyle == FontStyle.Italic && sameOutlines(regularTypeface, resolved)
    private val typeface: Typeface = if (syntheticItalic) regularTypeface else resolved

    private fun sameOutlines(a: Typeface, b: Typeface): Boolean {
        if (a == b) return true
        val left = Rect()
        val right = Rect()
        TextPaint().apply { typeface = a; textSize = PROBE_TEXT_SIZE }.getTextBounds("n", 0, 1, left)
        TextPaint().apply { typeface = b; textSize = PROBE_TEXT_SIZE }.getTextBounds("n", 0, 1, right)
        return left == right
    }
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        // Minikin truncates the font size to whole pixels unless linear metrics are enabled.
        isLinearText = true
        textSize = with(density) { textStyle.fontSize.toPx() }
        this.typeface = this@RubyFontResources.typeface
        isFakeBoldText = syntheticBold
        letterSpacing = when {
            textStyle.letterSpacing.isEm -> textStyle.letterSpacing.value
            textStyle.letterSpacing.isSp -> with(density) { textStyle.letterSpacing.toPx() } / textSize
            else -> 0f
        }
        fontFeatureSettings = textStyle.fontFeatureSettings
        // TextStyle.textGeometricTransform is never null - it defaults to a transform with
        // skewX 0 - so the synthetic italic skew has to be ADDED to it, not applied instead of it.
        val geometric = textStyle.textGeometricTransform
        if (geometric != null) textScaleX = geometric.scaleX
        textSkewX = (geometric?.skewX ?: 0f) + if (syntheticItalic) SYNTHETIC_ITALIC_SKEW else 0f
        textStyle.localeList?.takeIf { !it.isEmpty() }?.let {
            textLocales = android.os.LocaleList.forLanguageTags(it.joinToString(",") { locale -> locale.toLanguageTag() })
        }
    }
    private val emRatios = HashMap<Font, FontTypoRatio?>()
    /** Immutable font facts; callers must not pass this object to APIs that overwrite metrics. */
    internal val metricsInt: Paint.FontMetricsInt by lazy { paint.fontMetricsInt }
    internal val hanPunctuation by lazy { HanPunctuationFont(paint) }
    private var primaryHeight: Pair<Float, Float>? = null

    /**
     * Blink's ComputeEmHeight: unite every run font's normalized OS/2 typo box, cap it by the
     * primary font's hhea box, and return the result in pixels.
     */
    private fun emHeight(glyphs: PositionedGlyphs): RubyEmHeight? {
        var ascent = 0f
        var descent = 0f
        var found = false
        var previous: Font? = null
        for (index in 0 until glyphs.glyphCount()) {
            val font = glyphs.getFont(index)
            if (font === previous) continue
            previous = font
            val ratio = if (emRatios.containsKey(font)) emRatios[font] else
                FontTypoMetrics.ratio(font).also { emRatios[font] = it }
            if (ratio != null) {
                ascent = maxOf(ascent, ratio.ascent * paint.textSize)
                descent = maxOf(descent, ratio.descent * paint.textSize)
                found = true
            }
        }
        if (!found) return null
        val cap = primaryHeight ?: paint.fontMetrics.let { -it.ascent to it.descent }.also { primaryHeight = it }
        // Blink ComputeEmHeight rounds each united em extent up before capping by the primary font.
        return RubyEmHeight(minOf(ceil(ascent), cap.first), minOf(ceil(descent), cap.second))
    }

    // One CJK fallback-font probe per metric profile; no Compose paragraph or layout is created.
    private var strut: Pair<Float, Float>? = null
    fun strut(profilingId: Long): Pair<Float, Float> {
        strut?.let { return it }
        return LookupProfiler.section(profilingId, "ruby.font.strut") {
            if (Build.VERSION.SDK_INT >= 31) nativeStrut(paint, rtl)
            else paint.fontMetrics.let { -it.ascent to it.descent }
        }.also { strut = it }
    }

    /**
     * Shaped runs keyed by the exact shaping input. Ruby bases and readings repeat heavily across
     * a popup, and the shaped result depends only on this font resource, the text and the range.
     */
    private class ShapeKey(val text: String, val start: Int, val count: Int) {
        override fun hashCode(): Int = (text.hashCode() * 31 + start) * 31 + count
        override fun equals(other: Any?): Boolean = other is ShapeKey && other.start == start &&
            other.count == count && other.text == text
    }
    private val shapedRuns = HashMap<ShapeKey, RubyGlyphRun>()

    fun shape(text: String, start: Int, count: Int, chars: CharArray?): RubyGlyphRun {
        val key = ShapeKey(text, start, count)
        shapedRuns[key]?.let { return it }
        val run = if (Build.VERSION.SDK_INT >= 31) {
            val shaped = TextRunShaper.shapeTextRun(text, start, count, 0, text.length, 0f, 0f, rtl, paint)
            PositionedRubyGlyphs(shaped, emHeight(shaped))
        } else {
            val context = checkNotNull(chars)
            CanvasRubyGlyphs(text, start, start + count, rtl, paint.fontMetrics,
                paint.getTextRunAdvances(context, start, count, 0, context.size, rtl, null, 0))
        }
        // Bounded so one very large lookup cannot retain every annotation it ever shaped.
        if (shapedRuns.size >= 4096) shapedRuns.clear()
        shapedRuns[key] = run
        return run
    }

    fun characterOffset(text: String, start: Int, end: Int, x: Float, width: Float): Int {
        val advance = (if (rtl) width - x else x).coerceIn(0f, width)
        var offset = paint.getOffsetForAdvance(text, start, end, 0, text.length, rtl, advance)
        if (offset > start && paint.getRunAdvance(text, start, end, 0, text.length, rtl, offset) > advance) {
            offset = paint.getTextRunCursor(text, start, end, rtl, offset, Paint.CURSOR_BEFORE)
        }
        return offset.coerceIn(start, end - 1)
    }
}

public class RubyTextStyle(val style: TextStyle, val font: RubyFontResources, density: Density) {
    val paint = TextPaint(font.paint).apply {
        color = this@RubyTextStyle.style.color.takeIf { it.isSpecified }?.toArgb() ?: android.graphics.Color.BLACK
        this@RubyTextStyle.style.textDecoration?.let {
            isUnderlineText = TextDecoration.Underline in it
            isStrikeThruText = TextDecoration.LineThrough in it
        }
        this@RubyTextStyle.style.shadow?.let { setShadowLayer(it.blurRadius, it.offset.x, it.offset.y, it.color.toArgb()) }
    }
    val lineHeightPx = with(density) { when {
        style.lineHeight.isSp -> style.lineHeight.toPx()
        style.lineHeight.isEm -> style.lineHeight.value * font.paint.textSize
        else -> 0f
    } }
    val baselineShiftPx = (style.baselineShift?.multiplier ?: 0f) * -font.paint.ascent()
}

public class RubyTextPiece(val glyphs: RubyGlyphRun, val resource: RubyTextStyle, val x: Float, val length: Int)

/** Width and glyph ink are retained once; painting never runs text measurement. */
public class PreparedRubyText(
    val text: String,
    val style: TextStyle,
    private val pieces: List<RubyTextPiece>,
    val advancePx: Float,
    val ascentPx: Float,
    val descentPx: Float,
    private val halfLeadingPx: Float,
    val lineHeightPx: Float,
    val emAscentPx: Float?,
    val emDescentPx: Float?,
    val fontSizePx: Float,
) {
    val heightPx: Float get() = ascentPx + descentPx + halfLeadingPx * 2f
    val size = IntSize(ceil(advancePx).toInt(), ceil(heightPx).toInt())
    val firstBaseline: Float get() = ascentPx + halfLeadingPx
    val debugFonts: String get() = pieces.joinToString("|") { it.glyphs.debugFont }
    val hasStaleResolvedFonts: Boolean get() = pieces.any { it.resource.font.isStale }
    /** Only standalone headwords request ink bounds; body preparation does not scan glyph bounds. */
    private var measuredInk: RectF? = null
    val inkBounds: RectF get() {
        measuredInk?.let { return it }
        val bounds = RectF()
        pieces.forEach { piece ->
            val ink = piece.glyphs.inkBounds(piece.resource.paint)
            ink.offset(piece.x, firstBaseline - piece.resource.baselineShiftPx)
            bounds.union(ink)
        }
        return bounds.also { measuredInk = it }
    }

    fun characterOffset(x: Float): Int {
        var start = 0
        for (piece in pieces) {
            val end = start + piece.length
            if (x < piece.x + piece.glyphs.advancePx || end == text.length) {
                return piece.resource.font.characterOffset(text, start, end, x - piece.x, piece.glyphs.advancePx)
            }
            start = end
        }
        return 0
    }

    fun appendTo(builder: RubyGlyphBatchBuilder, x: Float, top: Float) {
        val baseline = top + firstBaseline
        pieces.forEach { piece -> piece.glyphs.appendTo(builder, x + piece.x,
            baseline - piece.resource.baselineShiftPx, piece.resource.paint) }
    }
}

public interface RubyGlyphRun {
    val advancePx: Float
    val ascentPx: Float
    val descentPx: Float
    val emHeight: RubyEmHeight?
    val debugFont: String get() = ""
    fun inkBounds(paint: TextPaint): RectF
    fun appendTo(builder: RubyGlyphBatchBuilder, x: Float, baseline: Float, paint: TextPaint)
}

private class CanvasRubyGlyphs(private val text: String, private val start: Int, private val end: Int,
    private val rtl: Boolean, metrics: Paint.FontMetrics,
    override val advancePx: Float) : RubyGlyphRun {
    override val ascentPx = -metrics.ascent
    override val descentPx = metrics.descent
    override val emHeight: RubyEmHeight? = null
    override fun inkBounds(paint: TextPaint): RectF {
        val bounds = Rect()
        paint.getTextBounds(text, start, end, bounds)
        return RectF(bounds)
    }
    override fun appendTo(builder: RubyGlyphBatchBuilder, x: Float, baseline: Float, paint: TextPaint) {
        builder.text(text, start, end, rtl, x, baseline, paint)
    }
}

@RequiresApi(31)
private class PositionedRubyGlyphs(private val glyphs: PositionedGlyphs,
    override val emHeight: RubyEmHeight?) : RubyGlyphRun {
    override val debugFont: String get() {
        val font = glyphs.getFont(0)
        return "${font.file?.name}#${font.ttcIndex}:y${glyphs.getGlyphY(0)}"
    }
    override val advancePx = glyphs.advance
    override val ascentPx = -glyphs.ascent
    override val descentPx = glyphs.descent
    override fun inkBounds(paint: TextPaint): RectF {
        val bounds = RectF()
        val glyph = RectF()
        for (index in 0 until glyphs.glyphCount()) {
            glyphs.getFont(index).getGlyphBounds(glyphs.getGlyphId(index), paint, glyph)
            glyph.offset(glyphs.getGlyphX(index), glyphs.getGlyphY(index))
            bounds.union(glyph)
        }
        return bounds
    }
    override fun appendTo(builder: RubyGlyphBatchBuilder, x: Float, baseline: Float, paint: TextPaint) {
        var start = 0
        while (start < glyphs.glyphCount()) {
            val font = glyphs.getFont(start)
            var end = start + 1
            while (end < glyphs.glyphCount() && glyphs.getFont(end) == font) end++
            builder.glyphs(font, glyphs, start, end, x, baseline, paint)
            start = end
        }
    }
}

@RequiresApi(31)
private fun nativeStrut(paint: TextPaint, rtl: Boolean): Pair<Float, Float> =
    TextRunShaper.shapeTextRun("\u6f22", 0, 1, 0, 1, 0f, 0f, rtl, paint).let { -it.ascent to it.descent }
