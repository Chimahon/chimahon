package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.icu.text.BreakIterator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import chimahon.renderer.DictionaryCore
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Bidi
import java.text.CharacterIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

public data class NativeDrawingInput(val raw: String, val css: String, val width: Int,
    val identity: List<StructuredNode> = emptyList(), val cssIdentity: ParsedCss = ParsedCss.EMPTY)

public data class NativeDrawingTiming(val inputMs: Double = 0.0, val measureMs: Double = 0.0,
    val finishMs: Double = 0.0, val transferMs: Double = 0.0, val releaseMs: Double = 0.0,
    val configurationMs: Double = 0.0)

/** One shared native batch, one platform measurement phase, one native finish. No callbacks. */
public class NativeCoreCompiler(
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
    private val secondary: Color,
    private val border: Color,
    private val resources: NativePreparationStats = NativePreparationStats(),
) : Closeable {
    private data class ContextKey(val css: List<String>, val font: Float, val dark: Boolean, val expanded: Boolean)
    private data class FontKey(val family: FontFamily?, val size: Float, val weight: Int, val flags: Int, val spacing: Float)
    private data class BatchContext(val handle: Long, val sourceSheets: IntArray)
    private val contexts = HashMap<ContextKey, BatchContext>()
    private val fontResources = HashMap<FontKey, NativePlatformFont>()
    private val fontEmRatios = HashMap<Typeface, FontTypoRatio?>()
    public var lastTiming: NativeDrawingTiming = NativeDrawingTiming()
        private set

    public fun precompile(css: String, style: TextStyle, dark: Boolean = false, expanded: Boolean = false) {
        precompileBatch(listOf(css), style, dark, expanded)
    }

    public fun precompileBatch(css: List<String>, style: TextStyle, dark: Boolean = false, expanded: Boolean = false) {
        context(css, style, dark, expanded)
    }

    private fun context(css: List<String>, style: TextStyle, dark: Boolean, expanded: Boolean): BatchContext {
        val key = ContextKey(css, style.fontSize.value * density.fontScale, dark, expanded)
        return contexts.getOrPut(key) {
            val sheets = css.distinct()
            val indices = sheets.withIndex().associate { it.value to it.index }
            val sourceSheets = IntArray(css.size) { indices.getValue(css[it]) }
            val handle = DictionaryCore.openBatch(sheets.toTypedArray(), key.font, dark, expanded).also { check(it != 0L) }
            BatchContext(handle, sourceSheets)
        }
    }

    public fun prepare(raw: String, cssText: String, style: TextStyle, width: Int,
        dark: Boolean = false, expanded: Boolean = false, identity: List<StructuredNode> = emptyList(),
        cssIdentity: ParsedCss = ParsedCss.EMPTY, checkCancellation: () -> Unit = {}): NativeGlossaryDocument =
        prepareBatch(listOf(NativeDrawingInput(raw, cssText, width, identity, cssIdentity)), style, dark, expanded, checkCancellation).single()

    public fun prepareBatch(inputs: List<NativeDrawingInput>, style: TextStyle, dark: Boolean = false,
        expanded: Boolean = false, checkCancellation: () -> Unit = {}): List<NativeGlossaryDocument> {
        if (inputs.isEmpty()) return emptyList()
        checkCancellation()
        val configuration = System.nanoTime()
        val context = context(inputs.map { it.css }, style, dark, expanded)
        val start = System.nanoTime()
        val handle = DictionaryCore.prepareDrawing(context.handle, inputs.map { it.raw }.toTypedArray(),
            Array(inputs.size) { nativeCardTape(inputs[it].identity) }, context.sourceSheets,
            FloatArray(inputs.size) { inputs[it].width / density.density }, style.color.toArgb(), border.toArgb())
        check(handle != 0L)
        try {
            val prepared = System.nanoTime()
            val counts = DictionaryCore.drawingCounts(handle)
            fun section(index: Int): ByteBuffer = DictionaryCore.drawingSection(handle, index).order(ByteOrder.nativeOrder())
            val fontData = section(0)
            val names = ByteArray(counts[1]).also { section(1).get(it) }
            val characters = CharArray(counts[2]).also { section(2).asCharBuffer().get(it) }
            val requests = IntArray(counts[3] * 5).also { section(3).asIntBuffer().get(it) }
            val textContexts = IntArray(counts[4] * 4).also { section(4).asIntBuffer().get(it) }
            val shapingRuns = IntArray(counts[15] * 2).also { section(15).asIntBuffer().get(it) }
            val fonts = Array(counts[0]) { index ->
                val at = index * 68
                val flags = fontData.getInt(at + 20)
                val name = String(names, fontData.getInt(at + 60), fontData.getInt(at + 64), Charsets.UTF_8).trim().lowercase(Locale.ROOT)
                val family = when (name) {
                    "monospace" -> FontFamily.Monospace
                    "sans-serif" -> FontFamily.SansSerif
                    "serif" -> FontFamily.Serif
                    else -> style.fontFamily
                }
                val key = FontKey(family, fontData.getFloat(at), fontData.getInt(at + 16), flags and 7, fontData.getFloat(at + 8))
                fontResources[key]?.takeUnless { it.state.value !== it.typeface } ?: run {
                    val state = resolver.resolve(family, FontWeight(key.weight.coerceIn(1, 1000)),
                        if (flags and 1 != 0) FontStyle.Italic else FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
                    val face = state.value as Typeface
                    NativePlatformFont(TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                        typeface = face
                        textSize = key.size
                        isLinearText = true
                        isFakeBoldText = !face.isBold && key.weight >= 600
                        if (flags and 1 != 0) {
                            val regular = resolver.resolve(family, FontWeight(key.weight.coerceIn(1, 1000)),
                                FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value as Typeface
                            val upright = android.graphics.Rect()
                            val italic = android.graphics.Rect()
                            Paint().apply { typeface = regular; textSize = 100f }.getTextBounds("n", 0, 1, upright)
                            Paint().apply { typeface = face; textSize = 100f }.getTextBounds("n", 0, 1, italic)
                            if (upright == italic) { typeface = regular; textSkewX = -0.25f }
                        }
                        letterSpacing = if (key.size > 0) key.spacing / key.size else 0f
                        isUnderlineText = flags and 2 != 0
                        isStrikeThruText = flags and 4 != 0
                        textLocale = Locale.JAPANESE
                    }, state, face).also { fontResources[key] = it }
                }
            }
            val advances = FloatArray(characters.size)
            val breaks = ByteArray(characters.size)
            val levels = ByteArray(characters.size)
            val breaker = BreakIterator.getLineInstance(Locale.forLanguageTag("ja-u-lb-normal"))
            for (at in textContexts.indices step 4) {
                checkCancellation()
                val from = textContexts[at + 1]
                val count = textContexts[at + 2]
                if (count == 0) continue
                breaker.setText(DrawingCharacterIterator(characters, from, from + count))
                var boundary = breaker.first()
                while (boundary != BreakIterator.DONE) {
                    if (boundary > from) breaks[boundary - 1] = 1
                    boundary = breaker.next()
                }
                val bidi = Bidi(characters, from, null, 0, count, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
                for (i in 0 until count) levels[from + i] = bidi.getLevelAt(i).toByte()
            }
            // Extents depend on the font, not the text run or available width.
            val fontMetrics = arrayOfNulls<FloatArray>(fonts.size)
            val metrics = FloatArray(counts[3] * 4)
            for (i in 0 until counts[3]) {
                if (i and 63 == 0) checkCancellation()
                val at = i * 5
                val fontIndex = requests[at]
                val font = fonts[fontIndex]
                val extents = fontMetrics[fontIndex] ?: run {
                    val ratio = if (fontEmRatios.containsKey(font.typeface)) fontEmRatios[font.typeface] else {
                        val resolved = if (android.os.Build.VERSION.SDK_INT >= 31) {
                            val glyphs = android.graphics.text.TextRunShaper.shapeTextRun("\u6f22", 0, 1, 0, 1, 0f, 0f, false, font.paint)
                            if (glyphs.glyphCount() > 0) FontTypoMetrics.ratio(glyphs.getFont(0)) else null
                        } else null
                        fontEmRatios[font.typeface] = resolved
                        resolved
                    }
                    val height = (font.metrics.descent - font.metrics.ascent).coerceAtLeast(0.01f)
                    // Preserve device-pixel rounding for CSS-pixel ruby extents.
                    val pixels = density.density
                    val emAscent = if (ratio == null) -font.metrics.ascent * font.paint.textSize / height else
                        minOf(ceil(ratio.ascent * font.paint.textSize * pixels) / pixels, -font.metrics.ascent)
                    val emDescent = if (ratio == null) font.metrics.descent * font.paint.textSize / height else
                        minOf(ceil(ratio.descent * font.paint.textSize * pixels) / pixels, font.metrics.descent)
                    floatArrayOf(font.metrics.ascent, font.metrics.descent, emAscent, emDescent)
                        .also { fontMetrics[fontIndex] = it }
                }
                extents.copyInto(metrics, i * 4)
                val end = requests[at + 1] + requests[at + 2]
                for (offset in requests[at + 1] until end) {
                    val kind = font.punctuation.type(characters[offset]).ordinal
                    breaks[offset] = (breaks[offset].toInt() or (kind shl 1)).toByte()
                }
            }
            for (group in shapingRuns.indices step 2) {
                checkCancellation()
                val first = shapingRuns[group] * 5
                val last = (shapingRuns[group] + shapingRuns[group + 1] - 1) * 5
                val paint = fonts[requests[first]].paint
                var from = requests[first + 1]
                val end = requests[last + 1] + requests[last + 2]
                while (from < end) {
                    var until = from + 1
                    while (until < end && levels[until] == levels[from]) until++
                    paint.getTextRunAdvances(characters, from, until - from, requests[first + 3], requests[first + 4],
                        levels[from].toInt() and 1 != 0, advances, from)
                    from = until
                }
            }
            section(5).asFloatBuffer().put(metrics)
            section(6).asFloatBuffer().put(advances)
            section(7).put(breaks)
            section(8).put(levels)
            val measured = System.nanoTime()
            checkCancellation()
            DictionaryCore.finishDrawing(handle)
            val finished = System.nanoTime()
            val resultCounts = DictionaryCore.drawingCounts(handle)
            fun copied(index: Int, bytes: Int) = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()).apply {
                put(section(index)); rewind()
            }
            val commands = copied(9, resultCounts[5] * 64)
            val hits = copied(10, resultCounts[6] * 28)
            val geometry = section(11)
            val imageNodes = if (counts[8] == 0) emptyList() else {
                val pool = ByteArray(counts[10]).also { section(12).get(it) }
                val properties = section(13)
                val images = section(14)
                fun text(offset: Int, count: Int) = String(pool, offset, count, Charsets.UTF_8)
                List(counts[8]) { image ->
                    val first = images.getInt(image * 12 + 4)
                    val count = images.getInt(image * 12 + 8)
                    StructuredNode.Element(StructuredTag.Image, attributes = StructuredAttributes(properties = buildMap {
                        repeat(count) { n -> val at = (first + n) * 16
                            put(text(properties.getInt(at), properties.getInt(at + 4)), text(properties.getInt(at + 8), properties.getInt(at + 12))) }
                    }))
                }
            }
            resources.paragraphs += counts[11]
            resources.rubyPairs += counts[12]
            resources.tables += counts[13]
            val documents = inputs.mapIndexed { source, input ->
                val at = source * 24
                val height = ceil(geometry.getFloat(at + 4) * density.density).toInt().coerceAtLeast(1)
                val first = geometry.getInt(at + 8)
                val count = geometry.getInt(at + 12)
                val surface = NativeCommandSurface(commands, hits, requests, textContexts, characters, fonts, source,
                    first, count, geometry.getInt(at + 16), geometry.getInt(at + 20), density.density, input.width, height)
                val items = ArrayList<NativeGlossaryItem>()
                items.add(surface)
                for (i in first until first + count) {
                    val c = i * 64
                    if (commands.getInt(c) == 2) items.add(NativeGlossaryImage(
                        (commands.getFloat(c + 24) * density.density).roundToInt(), (commands.getFloat(c + 28) * density.density).roundToInt(),
                        (commands.getFloat(c + 32) * density.density).roundToInt().coerceAtLeast(1),
                        (commands.getFloat(c + 36) * density.density).roundToInt().coerceAtLeast(1), imageNodes[commands.getInt(c + 8)]))
                }
                NativeGlossaryDocument(input.identity, input.cssIdentity, style, emptyList(), density, direction, resolver,
                    secondary, border, input.width, height, items, 0)
            }
            lastTiming = NativeDrawingTiming((prepared - start) / 1e6, (measured - prepared) / 1e6,
                (finished - measured) / 1e6, (System.nanoTime() - finished) / 1e6,
                configurationMs = (start - configuration) / 1e6)
            return documents
        } finally {
            val release = System.nanoTime()
            DictionaryCore.closeDrawing(handle)
            lastTiming = lastTiming.copy(releaseMs = (System.nanoTime() - release) / 1e6)
        }
    }

    override fun close() { contexts.values.forEach { DictionaryCore.closeContext(it.handle) }; contexts.clear(); fontResources.clear(); fontEmRatios.clear() }
}

private class DrawingCharacterIterator(private val text: CharArray, private val begin: Int,
    private val end: Int, private var position: Int = begin) : CharacterIterator {
    override fun current(): Char = if (position in begin until end) text[position] else CharacterIterator.DONE
    override fun first(): Char { position = begin; return current() }
    override fun last(): Char { position = if (end > begin) end - 1 else end; return current() }
    override fun next(): Char { if (position < end) position++; return current() }
    override fun previous(): Char { if (position <= begin) return CharacterIterator.DONE; position--; return current() }
    override fun setIndex(index: Int): Char { require(index in begin..end); position = index; return current() }
    override fun getBeginIndex(): Int = begin
    override fun getEndIndex(): Int = end
    override fun getIndex(): Int = position
    public override fun clone(): Any = DrawingCharacterIterator(text, begin, end, position)
}

public fun nativeCoreSource(raw: String): List<StructuredNode> = listOf(
    StructuredNode.Element(StructuredTag.Unknown, attributes = StructuredAttributes(properties = mapOf("nativeSource" to raw))))

public fun nativeCoreRaw(nodes: List<StructuredNode>): String = if (nativeCardTape(nodes) != null) "" else
    requireNotNull((nodes.singleOrNull() as? StructuredNode.Element)?.attributes?.properties?.get("nativeSource")) { "Native source identity missing" }

public fun nativeCoreSourceSize(nodes: List<StructuredNode>): Int =
    nativeCardTape(nodes)?.remaining() ?: nativeCoreRaw(nodes).length

public fun nativeCoreStylesheet(base: String, dictionary: String, variables: Map<String, String> = emptyMap()): String = buildString {
    append(base); append("\n:root{")
    variables.forEach { (name, value) -> append(if (name.startsWith("--")) name else "--$name"); append(':'); append(value); append(';') }
    append("}\n"); append(dictionary)
}
