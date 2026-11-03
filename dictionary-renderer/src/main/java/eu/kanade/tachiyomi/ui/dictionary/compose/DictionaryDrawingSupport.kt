package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

public const val DEFAULT_LINE_HEIGHT_FACTOR = 1.4f

public class RubyMetrics(
    val widthPx: Int,
    val heightPx: Int,
    /** Reading's top relative to the prepared ruby box. */
    val readingOffsetPx: Float,
    /** Base's top relative to the prepared ruby box. */
    val baseOffsetPx: Float,
    val baseText: String,
    val readingText: String,
    val baseLayout: PreparedRubyText,
    val readingLayout: PreparedRubyText,
    val readingStyle: TextStyle? = null,
    val inlineOffsetPx: Float = 0f,
)

public fun measureRubyInline(
    renderer: RubyTextRenderer,
    base: String,
    reading: String,
    style: TextStyle,
    readingStyle: TextStyle = rubyReadingStyle(style),
): RubyMetrics {
    return renderer.column(base, reading, style, readingStyle)
}




public val LocalMediaDataUris = compositionLocalOf<Map<String, String>> { emptyMap() }

/** Shared CSS line-box style for document preparation and the host. */
public fun dictionaryBaseStyle(color: Color, fontSize: Int, fontFamily: FontFamily?): TextStyle =
    TextStyle(
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * DEFAULT_LINE_HEIGHT_FACTOR).sp,
        fontFamily = fontFamily ?: FontFamily.Default,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

/** A text style's font size in sp, or 16 when unspecified (the em base the CSS parser assumes). */
public fun fontSizeSp(style: TextStyle): Float = style.fontSize.let { if (it.isSp) it.value else 16f }

/**
 * Line-box metrics for one text style, per CSS Inline Layout 3 §5.3:
 *
 *     L = line-height - (A + D),   A' = A + L/2,   D' = D + L/2
 *
 * A and D are the first available font's ascent and descent; leading may be negative.
 */
public class LineBoxMetrics(
    val ascentPx: Float,
    val descentPx: Float,
    val halfLeadingPx: Float,
    val firstBaselinePx: Float,
)

public const val LOOKUP_SCAN_MAX_CHARS = 24

// Whitespace (incl. U+3000) plus the CJK/ASCII punctuation that terminates a scan.
// Raw string so `\[` and `\]` are regex escapes for literal brackets; writing `[\\]` instead
// escapes the closing bracket and produces an unclosed character class.
private val SCAN_BOUNDARY_REGEX =
    Regex("""[\s　。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}\[\]・：；:;，,.─]""")

// \w plus Latin-1 Supplement/Extended-A, Arabic, and Hangul - the set the WebView accepts.
private val WORD_CHAR_REGEX = Regex("""[\wÀ-ɏ؀-ۿᄀ-ᇿ㄰-㆏㐀-䶿가-힯]""")


/**
 * CJK ideographs and kana, plus Hangul - Korean recursive lookup is a supported feature here, so
 * the reference renderer deliberately treats Hangul as CJK for tap-targeting purposes.
 */
public fun Char.isLookupCjk(): Boolean = when (this) {
    in '぀'..'ヿ' -> true // hiragana + katakana
    in '　'..'鿿' -> true // CJK radicals through CJK unified ideographs (incl. U+3000)
    in '豈'..'﫿' -> true // CJK compatibility ideographs
    in '＀'..'￯' -> true // halfwidth and fullwidth forms
    in 'ᄀ'..'ᇿ' -> true // Hangul jamo
    in '㄰'..'㆏' -> true // Hangul compatibility jamo
    in '㐀'..'䶿' -> true // Hangul extended-A
    in '가'..'힯' -> true // Hangul syllables
    else -> false
}

private fun Char.isLookupWordChar(): Boolean = isLookupCjk() || WORD_CHAR_REGEX.matches(this.toString())

private fun Char.isScanBoundary(): Boolean = SCAN_BOUNDARY_REGEX.matches(this.toString())

/**
 * The word a tap at [offset] should look up.
 *
 * Mirrors the reference renderer's `extractTextAtPoint`, which uses `caretRangeFromPoint` to turn
 * a tap into a character offset and then expands it. Without this the whole rendered run is the
 * lookup target, so tapping one character in a sentence searched for the entire sentence.
 *
 * CJK expands FORWARD only (up to [LOOKUP_SCAN_MAX_CHARS]) because there are no spaces to expand
 * within and the backend handles deinflection of the resulting prefix; other scripts expand in both
 * directions to word characters.
 *
 * Returns null when the offset is not on a word character, so a tap on punctuation does nothing.
 */
public fun lookupWordAt(text: String, offset: Int): String? {
    if (text.isEmpty()) return null
    val i = offset.coerceIn(0, text.length - 1)
    val startChar = text[i]
    if (startChar.isScanBoundary() || !startChar.isLookupWordChar()) return null

    if (startChar.isLookupCjk()) {
        var end = i
        val limit = minOf(text.length, i + LOOKUP_SCAN_MAX_CHARS)
        while (end < limit && !text[end].isScanBoundary()) end++
        return text.substring(i, end).takeIf { it.isNotEmpty() }
    }

    var start = i
    while (start > 0 && !text[start - 1].isScanBoundary() && text[start - 1].isLookupWordChar()) start--
    var end = i + 1
    while (end < text.length && !text[end].isScanBoundary() && text[end].isLookupWordChar()) end++
    return text.substring(start, end).takeIf { it.isNotEmpty() }
}

private val QUERY_PARAM_REGEX = Regex("[?&]([^=]+)=([^&]+)")

public fun extractQuery(href: String): String? {
    val params = QUERY_PARAM_REGEX.findAll(href)
    return params.asSequence().mapNotNull { m ->
        if (m.groupValues[1] == "query") m.groupValues[2].replace("+", " ") else null
    }.firstOrNull()?.let { raw ->
        // WebView's URLSearchParams.get() percent-decodes; match it so kanji
        // queries (`?query=%E4%B8%80…`) look up instead of missing.
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
}

public fun resolveMediaUri2(dictName: String, path: String, map: Map<String, String>): String? {
    if (map.isEmpty()) return null
    val cleaned = path.removePrefix("media://").removePrefix("media:").trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        map[candidate]?.let { return it }
    }
    return null
}
