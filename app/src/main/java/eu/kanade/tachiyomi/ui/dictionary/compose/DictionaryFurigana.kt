package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.turtlekazu.furiganable.compose.m3.TextWithReading

/**
 * Renders a term with ruby annotations above kanji using Furiganable
 * (the same library yomihon uses), e.g. 「日本語」 with 「にほんご」 above.
 *
 * [splitfurigana] computes which reading characters belong to which kanji
 * run; Furiganable handles the actual sub-stack layout.
 */
@Composable
fun FuriganaText(
    expression: String,
    reading: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    rubyColor: Color? = null,
    maxBaseLines: Int = 2,
) {
    if (expression.isBlank()) return
    val formatted = buildFuriganaString(expression, reading)
    if (formatted == expression) {
        Text(
            text = expression,
            color = color,
            fontSize = fontSize,
            maxLines = maxBaseLines,
        )
        return
    }
    TextWithReading(
        formattedText = formatted,
        style = TextStyle(color = color, fontSize = fontSize),
        furiganaFontSize = fontSize * 0.45f,
        modifier = modifier,
    )
}

/**
 * Build the Furiganable markup string, e.g. `[日本語[にほんご]]` or
 * `漢[かん]字[じ]`, from an expression + full reading.
 */
fun buildFuriganaString(expression: String, reading: String): String {
    val segments = splitfurigana(expression, reading)
    if (segments.none { it.second.isNotBlank() }) return expression
    return buildString {
        for ((source, ruby) in segments) {
            if (ruby.isBlank()) {
                append(source)
            } else {
                append("[$source[$ruby]]")
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

/**
 * Split a term's [reading] so kanji runs receive ruby. Returns pairs
 * (source-run, ruby-reading) ready for rendering.
 *
 * Bounded, best-effort: correct when each kanji run maps to a same-length
 * hiragana run; exotic jukujikun readings may misplace kana but never crash.
 */
fun splitfurigana(expression: String, reading: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    if (expression.isEmpty()) return out
    var rIdx = 0
    var i = 0
    while (i < expression.length) {
        val c = expression[i]
        if (c.isJapaneseKana()) {
            val start = i
            while (i < expression.length && expression[i].isJapaneseKana()) i++
            val run = expression.substring(start, i)
            out.add(run to "")
            if (rIdx < reading.length && reading.startsWith(run, rIdx)) {
                rIdx += run.length
            }
        } else if (c.isKanji()) {
            val start = i
            while (i < expression.length && !expression[i].isJapaneseKana()) i++
            val run = expression.substring(start, i)
            val len = if (rIdx < reading.length) {
                kotlin.math.min(run.length, reading.length - rIdx)
            } else 0
            val rs = if (rIdx < reading.length) reading.substring(rIdx, rIdx + len) else ""
            out.add(run to rs)
            rIdx += len
        } else {
            val start = i
            while (i < expression.length && !expression[i].isJapaneseKana() && !expression[i].isKanji()) i++
            out.add(expression.substring(start, i) to "")
        }
    }
    if (rIdx < reading.length) {
        out.add(reading.substring(rIdx) to "")
    }
    return out
}
