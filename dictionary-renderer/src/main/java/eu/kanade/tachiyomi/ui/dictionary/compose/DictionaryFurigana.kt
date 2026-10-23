package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Reading size relative to the base, matching the CSS `rt { font-size: 50% }` UA default in
 * CSS Ruby 1, Appendix A.1. Single source of truth: this used to be duplicated across files.
 */
private const val RUBY_SIZE_RATIO = 0.5f

/**
 * `base.css` `.headword-furigana { font-size: 0.55em }` - the HEADWORD reading only.
 *
 * The renderer tags the headword's `rt` with `headword-furigana`, so the headword reading is 55%
 * of the base while a reading inside the gloss body falls back to the 50% UA default (which
 * 小学館 also states explicitly: `rt { font-size: 0.5em }`). Measured in Chrome for
 * 「模様替え」 at the headword size of 22.4px: `rt` computes to 12.32px = 0.55 x 22.4, not 11.2px.
 * Using 0.5 for both made every headword reading 10% too small, on every entry.
 */
public const val HEADWORD_RUBY_SIZE_RATIO = 0.55f

/** `base.css` `.headword-furigana { margin-left: 0.2em }`. */
public const val HEADWORD_RUBY_GAP_EM = 0.2f

/**
 * The reading's own style, and never inheriting text decoration.
 *
 * [sizeRatio] is the annotation's font size as a fraction of the base: 0.5 for a reading inside
 * the gloss body (the CSS Ruby 1 UA default), 0.55 for the headword, which base.css scales with
 * `.headword-furigana { font-size: 0.55em }`.
 */
public fun rubyReadingStyle(baseStyle: TextStyle, sizeRatio: Float = RUBY_SIZE_RATIO): TextStyle = baseStyle.copy(
    fontSize = baseStyle.fontSize * sizeRatio,
    // CSS Ruby 1, Appendix A.1: rt requests one em. RubyLayout also accounts for glyph ink
    // exceeding that requested box when reserving annotation clearance.
    lineHeight = baseStyle.fontSize * sizeRatio,
    textDecoration = null,
)

public val LocalRubyTextRenderer = compositionLocalOf<RubyTextRenderer?> { null }

@Composable
fun RubyRun(
    base: String,
    reading: String,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
    /**
     * Annotation size as a fraction of the base. The headword passes
     * [HEADWORD_RUBY_SIZE_RATIO] (base.css `.headword-furigana { font-size: 0.55em }`); a
     * reading inside the gloss body keeps the 0.5 UA default.
     */
    sizeRatio: Float = RUBY_SIZE_RATIO,
    annotationStyle: TextStyle? = null,
) {
    if (reading.isBlank() || base.isBlank()) {
        Text(
            text = base,
            style = baseStyle,
            modifier = modifier,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow,
        )
        return
    }
    val inheritedStyle = LocalTextStyle.current.merge(baseStyle)
    val nativeBaseStyle = if (inheritedStyle.color.isSpecified) inheritedStyle
        else inheritedStyle.copy(color = LocalContentColor.current)
    val readingStyle = if (annotationStyle != null) {
        if (annotationStyle.color.isSpecified) annotationStyle else annotationStyle.copy(color = nativeBaseStyle.color)
    } else remember(nativeBaseStyle, sizeRatio) { rubyReadingStyle(nativeBaseStyle, sizeRatio) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val renderer = LocalRubyTextRenderer.current ?: remember(density, direction, resolver) {
        RubyTextRenderer(density, direction, resolver)
    }
    // Reading font state here invalidates the retained column when async font resolution changes.
    val baseResource = renderer.resources(nativeBaseStyle)
    val readingResource = renderer.resources(readingStyle)
    val column = remember(base, reading, baseResource, readingResource) {
        chimahon.diagnostics.LookupProfiler.section(renderer.profilingId, "ruby.headword.prepare") {
            renderer.column(base, reading, nativeBaseStyle, readingStyle)
        }
    }
    val drawing = remember(column) {
        chimahon.diagnostics.LookupProfiler.section(renderer.profilingId, "ruby.headword.batch") { RubyParagraphDrawing.column(column) }
    }
    val box = remember(column) {
        column.standaloneBox()
    }
    Layout(content = {}, modifier = modifier.semantics { text = AnnotatedString(base) }.drawBehind {
        chimahon.diagnostics.LookupProfiler.section(renderer.profilingId, "ruby.headword.paint") {
            if (overflow == TextOverflow.Visible) drawing.draw(drawContext.canvas.nativeCanvas, -box.left, -box.top)
            else clipRect { drawing.draw(drawContext.canvas.nativeCanvas, -box.left, -box.top) }
        }
    }) { _, constraints ->
        layout(constraints.constrainWidth(box.width), constraints.constrainHeight(box.height),
            alignmentLines = mapOf(FirstBaseline to box.baseline, LastBaseline to box.baseline)) {
        }
    }
}

private fun Char.isJapaneseKana(): Boolean {
    val c = this.code
    return c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0x1B000..0x1B0FF
}

private fun Char.isKanji(): Boolean {
    val c = this.code
    return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF
}

private fun katakanaToHiragana(s: String): String =
    s.map { c ->
        val code = c.code
        if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else c
    }.joinToString("")

/**
 * A run of the headword produced by [distributeFurigana]: either a kana/other
 * run (empty reading) or a kanji run sharing a single [reading] chunk.
 */
private data class FuriganaRun(val text: String, val reading: String)

/**
 * Port of the WebView's `distributeFurigana` (`renderer.js` 1719-1816): splits
 * an expression+reading into runs, edge-matching leading/trailing kana and
 * distributing the middle reading across the kanji runs. Strict pass, then a
 * fallback pass that distributes proportionally (like the JS).
 */
private fun distributeFurigana(expression: String, reading: String): List<FuriganaRun> {
    if (reading.isBlank() || reading == expression) {
        return listOf(FuriganaRun(expression, ""))
    }
    val readingNorm = katakanaToHiragana(reading)

    fun isKanaRun(text: String): Boolean = text.all { it.isJapaneseKana() }

    // Group expression into kana / non-kana runs.
    val grouped = mutableListOf<String>()
    for (ch in expression) {
        val isKana = ch.isJapaneseKana()
        val last = grouped.lastOrNull()
        if (last != null && isKanaRun(last) == isKana) {
            grouped[grouped.size - 1] = last + ch
        } else {
            grouped.add(ch.toString())
        }
    }
    val groups = grouped.map { FuriganaRun(it, "") }

    if (groups.size == 1) {
        return if (isKanaRun(groups[0].text)) {
            listOf(FuriganaRun(expression, ""))
        } else {
            listOf(FuriganaRun(expression, reading))
        }
    }

    // Pass 1: strict Yomitan-style matching (kana must appear in reading).
    fun tryMatch(groupIdx: Int, readIdx: Int): List<FuriganaRun>? {
        if (groupIdx >= groups.size) {
            return if (readIdx >= reading.length) emptyList() else null
        }
        val g = groups[groupIdx]
        if (isKanaRun(g.text)) {
            val kn = katakanaToHiragana(g.text)
            if (readingNorm.startsWith(kn, readIdx)) {
                val rest = tryMatch(groupIdx + 1, readIdx + g.text.length)
                if (rest != null) return listOf(FuriganaRun(g.text, "")) + rest
            }
            return null
        } else {
            var result: List<FuriganaRun>? = null
            var i = reading.length
            while (i >= readIdx + g.text.length) {
                val rest = tryMatch(groupIdx + 1, i)
                if (rest != null) {
                    if (result != null) return null
                    result = listOf(FuriganaRun(g.text, reading.substring(readIdx, i))) + rest
                }
                i--
            }
            return result
        }
    }

    val strict = tryMatch(0, 0)
    if (strict != null) return strict

    // Pass 2: edge-match kana from start/end, distribute middle to kanji runs.
    var readPos = 0
    var gFront = 0
    while (gFront < groups.size && isKanaRun(groups[gFront].text)) {
        val kn = katakanaToHiragana(groups[gFront].text)
        if (readingNorm.startsWith(kn, readPos)) {
            readPos += groups[gFront].text.length
            gFront++
        } else {
            break
        }
    }

    var gBack = groups.size - 1
    var readBack = reading.length
    while (gBack >= gFront && isKanaRun(groups[gBack].text)) {
        val kn = katakanaToHiragana(groups[gBack].text)
        val endPos = readBack - groups[gBack].text.length
        if (endPos >= readPos && readingNorm.substring(endPos, readBack) == kn) {
            readBack = endPos
            gBack--
        } else {
            break
        }
    }

    val result = mutableListOf<FuriganaRun>()
    for (i in 0 until gFront) result.add(FuriganaRun(groups[i].text, ""))

    val middle = groups.subList(gFront, gBack + 1)
    if (middle.isNotEmpty()) {
        val midReading = reading.substring(readPos, readBack)
        val kanjiGroups = middle.filter { !isKanaRun(it.text) }
        val totalKanjiLen = kanjiGroups.sumOf { it.text.length }
        if (totalKanjiLen > 0) {
            var rp = readPos
            for (g in middle) {
                if (isKanaRun(g.text)) {
                    result.add(FuriganaRun(g.text, ""))
                } else {
                    val take = kotlin.math.round((g.text.length.toFloat() / totalKanjiLen) * midReading.length).toInt()
                    val segEnd = kotlin.math.min(rp + kotlin.math.max(take, g.text.length), readBack)
                    result.add(FuriganaRun(g.text, reading.substring(rp, segEnd)))
                    rp = segEnd
                }
            }
        } else {
            for (g in middle) result.add(FuriganaRun(g.text, ""))
        }
    }

    for (i in gBack + 1 until groups.size) result.add(FuriganaRun(groups[i].text, ""))

    if (result.any { it.reading.isNotEmpty() }) return result
    return listOf(FuriganaRun(expression, reading))
}

/**
 * A single unit of a term headword. Mirrors the WebView's headword DOM:
 * each kanji character becomes its own individually-tappable span
 * (`renderer.js` `appendWithKanjiSpans`), while kana/other runs are plain text.
 */
sealed interface HeadwordUnit {
    /** A single kanji character, tappable for a kanji-only lookup. */
    data class Kanji(val char: String, val reading: String) : HeadwordUnit

    /** A kana/other run; tapping it bubbles to the term-level lookup. */
    data class Text(val text: String) : HeadwordUnit

    /**
     * A run with no kanji that still carries a reading - "Adsorption" / アドsorプション,
     * ".NaCl" / えんさんえんちゅう, and so on. These are common and the reading used to be
     * discarded entirely, because only [Kanji] units could render an annotation. Annotated as a
     * whole run rather than per character, and deliberately not kanji-tappable.
     */
    data class Ruby(val text: String, val reading: String) : HeadwordUnit

    /** The reading this unit paints above itself, or null when it has none. */
    fun readingOrNull(): String? = when (this) {
        is Kanji -> reading.ifBlank { null }
        is Ruby -> reading.ifBlank { null }
        is Text -> null
    }
}

/**
 * Split a headword into per-unit segments for [Headword]: one [HeadwordUnit.Kanji]
 * per kanji character (reading distributed proportionally like `distributeFurigana`)
 * and one [HeadwordUnit.Text] per non-kanji run.
 */
fun splitHeadwordUnits(expression: String, reading: String): List<HeadwordUnit> {
    val out = mutableListOf<HeadwordUnit>()
    val runs = distributeFurigana(expression, reading)
    for (run in runs) {
        val kanjiCount = run.text.count { it.isKanji() }
        if (kanjiCount == 0) {
            // A kana reading on a non-kanji run is still a reading: keep it, annotated over the
            // whole run. Dropping it here is why "Adsorption" / アドUniversal rendered bare.
            if (run.reading.isNotBlank()) {
                out.add(HeadwordUnit.Ruby(run.text, run.reading))
            } else {
                out.add(HeadwordUnit.Text(run.text))
            }
            continue
        }
        val m = run.reading.length
        var kanjiSeen = 0
        val textBuffer = StringBuilder()
        for (c in run.text) {
            if (!c.isKanji()) {
                textBuffer.append(c)
                continue
            }
            if (textBuffer.isNotEmpty()) {
                out.add(HeadwordUnit.Text(textBuffer.toString()))
                textBuffer.clear()
            }
            // Floor-distribute the run's reading across its kanji chars; the
            // last char absorbs any remainder so nothing is dropped.
            val start = (kanjiSeen * m) / kanjiCount
            val end = ((kanjiSeen + 1) * m) / kanjiCount
            val charReading = if (m > 0) run.reading.substring(start, end) else ""
            out.add(HeadwordUnit.Kanji(c.toString(), charReading))
            kanjiSeen++
        }
        if (textBuffer.isNotEmpty()) {
            out.add(HeadwordUnit.Text(textBuffer.toString()))
        }
    }
    return out
}

/**
 * Headword rendered as a single-line row of independently-tappable units.
 * Furigana is drawn above each kanji character; tapping one routes a kanji-only
 * lookup. Tapping a kana run or the empty space bubbles to the term-level tap
 * (like the WebView's `.kanji-tappable` spans stopping propagation).
 */
@Composable
fun Headword(
    expression: String,
    reading: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    onKanjiTap: ((String) -> Unit)? = null,
) {
    val units = remember(expression, reading) { splitHeadwordUnits(expression, reading) }
    val baseLineHeight = fontSize * 1.2f
    val baseStyle = remember(color, fontSize, fontWeight, baseLineHeight) {
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = baseLineHeight,
        )
    }
    // Each RubyRun owns the full bounds of its reading and base; this row bottom-aligns units.
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        units.forEach { unit ->
            when (unit) {
                is HeadwordUnit.Text -> {
                    Text(
                        text = unit.text,
                        color = color,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        lineHeight = baseLineHeight,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                is HeadwordUnit.Kanji -> {
                    val tapModifier = if (onKanjiTap != null) Modifier.clickable { onKanjiTap(unit.char) } else Modifier
                    if (unit.reading.isNotBlank()) {
                        // base.css `.headword-furigana { margin-left: 0.2em }` separates
                        // each reading from the previous unit. This used to insert a spacer
                        // one full `fontSize` wide — 5x the specified gap.
                        Spacer(Modifier.width((fontSize.value * HEADWORD_RUBY_GAP_EM).dp))
                        RubyRun(
                            base = unit.char,
                            reading = unit.reading,
                            baseStyle = baseStyle,
                            modifier = tapModifier,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            sizeRatio = HEADWORD_RUBY_SIZE_RATIO,
                        )
                    } else {
                        Text(
                            text = unit.char,
                            color = color,
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            lineHeight = baseLineHeight,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = tapModifier,
                        )
                    }
                }
                is HeadwordUnit.Ruby -> {
                    // A reading over a non-kanji run. Same 0.2em separation as the per-kanji case,
                    // and no kanji-tap: "Adsorption" is not a kanji lookup.
                    Spacer(Modifier.width((fontSize.value * HEADWORD_RUBY_GAP_EM).dp))
                    RubyRun(
                        base = unit.text,
                        reading = unit.reading,
                        baseStyle = baseStyle,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        sizeRatio = HEADWORD_RUBY_SIZE_RATIO,
                    )
                }
            }
        }
    }
}
