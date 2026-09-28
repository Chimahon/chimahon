package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reading size relative to the base, matching the CSS `rt { font-size: 50% }` UA default that
 * every browser ships (CSS Ruby 1, Appendix A.1). Single source of truth: this used to be
 * duplicated as 0.5 / 0.55 / 0.6 across four files.
 */
private const val RUBY_SIZE_RATIO = 0.5f

/** `base.css` `.headword-furigana { margin-left: 0.2em }`. */
private const val HEADWORD_RUBY_GAP_EM = 0.2f

/** The reading's line box, as a multiple of its own (half-size) font. */
private const val RUBY_READING_LINE_HEIGHT = 1.1f

/**
 * How far the base is pulled up into the reading's line box, in ems of the base font.
 *
 * The reading is half the base size, so its line box is ~0.55em while the gap between the reading's
 * glyph bottom and the base's glyph top would otherwise be ~0.24em - a quarter of a character, which
 * reads as loose, stacked text rather than furigana. Browsers close this by letting the annotation's
 * box overlap the base's leading (CSS Ruby 1 §3.4), so the base is raised by this much and the rect
 * shrinks to match, keeping the base's baseline on the line baseline.
 */
internal const val RUBY_BASE_OVERLAP_EM = 0.3f

/**
 * A furigana run: reading stacked above base, in one pure-Compose layout node.
 *
 * Replaces `com.turtlekazu.furiganable`, whose Android implementation branches on
 * `Build.VERSION.SDK_INT` and, on API 28+, substitutes Compose's `Text` with a real `TextView`
 * hosted through `AndroidView` interop — one View plus a `SpannableString` and four
 * `android.text.style` spans per ruby run, and a fresh un-cached `TextMeasurer` per measurement.
 * `AndroidView` is the most expensive interop primitive Compose offers, and it is what forced the
 * surrounding line-height workaround and the divergent reading-size ratios.
 *
 * Geometry follows CSS Ruby 1 §3.1.1: the ruby column is as wide as the widest of base and
 * annotation, and each is centred inside it. `Modifier.width(IntrinsicSize.Max)` sizes that column
 * because a `Box` reports the maximum of its children's maximum intrinsic widths — so no second
 * measure pass and no manual text measurement is needed. The column is genuinely taller than the
 * base, so the enclosing flow's line box grows to contain it and callers must not inflate
 * `lineHeight` as well.
 *
 * A browser never paints `text-decoration` on the annotation (verified against the reference
 * renderer, including inside `<u>`/underlined spans and links) while still decorating the base, so
 * the reading drops it and the base keeps it.
 */
/** The reading's own style: half the base size, and never inheriting text decoration. */
internal fun rubyReadingStyle(baseStyle: TextStyle): TextStyle = baseStyle.copy(
    fontSize = baseStyle.fontSize * RUBY_SIZE_RATIO,
    lineHeight = (baseStyle.fontSize * RUBY_SIZE_RATIO) * RUBY_READING_LINE_HEIGHT,
    textDecoration = null,
)

/**
 * Vertical space a ruby run's reading occupies ABOVE its base.
 *
 * [RubyRun] paints the reading outside its own bounds (CSS Ruby 1 §3.6: the annotation contributes
 * no inline height), so whoever stacks ruby runs has to reserve this much room or the reading
 * collides with the line above it.
 */
internal fun rubyAnnotationHeight(baseStyle: TextStyle): Dp =
    ((baseStyle.fontSize * RUBY_SIZE_RATIO) * RUBY_READING_LINE_HEIGHT).value.dp

/**
 * The size a ruby run occupies, per CSS Ruby 1 §3.1.1: the column is as wide as the widest of base
 * and annotation, and tall enough for both stacked.
 *
 * Measured explicitly with a [androidx.compose.ui.text.TextMeasurer] rather than via
 * `Modifier.width(IntrinsicSize.*)`: intrinsic width threaded through a `Column` of `Text`s
 * resolved to the BASE width, so a reading wider than its base got clipped.
 */
internal fun measureRuby(
    measurer: androidx.compose.ui.text.TextMeasurer,
    base: String,
    reading: String,
    baseStyle: TextStyle,
): IntSize {
    val readingStyle = rubyReadingStyle(baseStyle)
    val baseSize = measurer.measure(AnnotatedString(base), baseStyle).size
    val readingSize = measurer.measure(AnnotatedString(reading), readingStyle).size
    return IntSize(
        width = maxOf(baseSize.width, readingSize.width),
        height = baseSize.height + readingSize.height,
    )
}

@Composable
fun RubyRun(
    base: String,
    reading: String,
    baseStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip,
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
    val readingStyle = remember(baseStyle) { rubyReadingStyle(baseStyle) }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    // Pixels -> dp MUST go through the layout density. `TextMeasurer` is not a `Density`, so the
    // tempting `with(measurer) { px.dp }` silently binds to the plain `Int.dp` extension and treats
    // pixels as dp - which made every ruby run `density` times too wide (2x at 2.0, 3x at 3.0) and
    // filled Japanese lines with gaps. `.dp` on a raw Int compiles, so nothing flagged it.
    val density = LocalDensity.current
    val columnWidth = remember(base, reading, baseStyle, readingStyle, density) {
        val basePx = measurer.measure(AnnotatedString(base), baseStyle).size.width
        val readingPx = measurer.measure(AnnotatedString(reading), readingStyle).size.width
        with(density) { maxOf(basePx, readingPx).toDp() }
    }
    Box(
        modifier = modifier
            .width(columnWidth)
            // Reserve the annotation's height INSIDE this box. The reading paints outside its own
            // bounds (below), so without this padding the run reports only the base's height and the
            // reading lands on top of whatever the previous line drew. Doing it here rather than by
            // inflating the caller's line-height means every container is correct by construction -
            // a nested `span` inside a gloss bypasses the flow that would otherwise add the leading.
            .padding(top = rubyAnnotationHeight(baseStyle)),
        // Both children are centred in the column. Without this they sit at the top-LEFT, so every
        // reading was visibly offset from the kanji it annotates and a page of ruby read as broken
        // spacing. CSS Ruby 1 §3.1.1: "the ruby text is centred within the ruby base".
        contentAlignment = Alignment.TopCenter,
    ) {
        // The base is ordinary text: it sits on the line's baseline, so a flow of these aligns
        // with the plain text around it.
        Text(
            text = base,
            style = baseStyle,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow,
        )
        // The reading is PAINTED above the base and contributes no height, which is what CSS Ruby 1
        // §3.6 specifies ("ruby annotation containers do not contribute to the measured height of a
        // line's inline contents"). Laying it out as a normal child made this box `reading + base`
        // tall with the reading's baseline first, so a FlowRow aligned the READING against
        // neighbouring text and pushed the base down. The top padding above is what gives it room.
        Layout(
            content = {
                Text(
                    text = reading,
                    style = readingStyle,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            },
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable.width, 0) {
                placeable.placeRelative(0, -placeable.height)
            }
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
    // `RubyRun` reserves the room its reading needs itself, so this row only has to bottom-align.
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
                    )
                }
            }
        }
    }
}
