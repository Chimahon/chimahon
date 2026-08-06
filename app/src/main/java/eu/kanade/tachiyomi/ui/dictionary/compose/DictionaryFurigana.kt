package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.turtlekazu.furiganable.compose.m3.TextWithReading

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
            out.add(HeadwordUnit.Text(run.text))
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
                        TextWithReading(
                            formattedText = "[${unit.char}[${unit.reading}]]",
                            style = TextStyle(
                                color = color,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                lineHeight = baseLineHeight,
                            ),
                            furiganaFontSize = fontSize * 0.55f,
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
            }
        }
    }
}