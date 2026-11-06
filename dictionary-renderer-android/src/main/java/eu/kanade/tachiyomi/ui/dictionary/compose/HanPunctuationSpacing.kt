package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Rect
import android.text.TextPaint

internal enum class HanPunctuation { OTHER, OPEN, CLOSE, MIDDLE, OPEN_NARROW, CLOSE_NARROW, FONT_DEPENDENT }

/** CSS Text 4 text-spacing classes; dot/colon/quote placement depends on the actual font. */
internal fun hanPunctuation(char: Char): HanPunctuation = when (char) {
    '\u3001', '\u3002', '\uff0c', '\uff0e', '\uff1a', '\uff1b',
    '\u2018', '\u2019', '\u201c', '\u201d' -> HanPunctuation.FONT_DEPENDENT
    '\u00b7', '\u2027', '\u30fb', '\u3000' -> HanPunctuation.MIDDLE
    '(', '[', '{' -> HanPunctuation.OPEN_NARROW
    ')', ']', '}' -> HanPunctuation.CLOSE_NARROW
    // These ranges contain no other opening/closing punctuation.
    in '\u0000'..'\u007f', in '\u3040'..'\u30ff', in '\u3400'..'\u9fff' -> HanPunctuation.OTHER
    else -> {
        val fullwidth = char in '\u3000'..'\u303f' || char in '\uff01'..'\uff60'
        when (Character.getType(char)) {
            Character.START_PUNCTUATION.toInt() -> if (fullwidth) HanPunctuation.OPEN else HanPunctuation.OPEN_NARROW
            Character.END_PUNCTUATION.toInt() -> if (fullwidth) HanPunctuation.CLOSE else HanPunctuation.CLOSE_NARROW
            else -> HanPunctuation.OTHER
        }
    }
}

/** Blink HanKerning::ShouldKernLast/ShouldKern, returning previous (-1) or current (0). */
internal fun hanKerningTarget(previous: HanPunctuation, current: HanPunctuation): Int? = when {
    previous == HanPunctuation.CLOSE && (current == HanPunctuation.CLOSE || current == HanPunctuation.MIDDLE || current == HanPunctuation.CLOSE_NARROW) -> -1
    current == HanPunctuation.OPEN && (previous == HanPunctuation.OPEN || previous == HanPunctuation.MIDDLE || previous == HanPunctuation.CLOSE || previous == HanPunctuation.OPEN_NARROW) -> 0
    else -> null
}

/** A CSS scope marker, not a measurement or drawing span. */
internal class NativeTextSpacingTrim(val value: String)

/** Worker-owned font facts, reused across fresh documents, not cached word layouts. */
internal class HanPunctuationFont(private val paint: TextPaint) {
    private val types = HashMap<Char, HanPunctuation>()
    private val features = paint.fontFeatureSettings.orEmpty()
    private val exclusive = FEATURE.findAll(features).any {
        it.groupValues[1] in EXCLUSIVE && it.groupValues[2] !in setOf("0", "off")
    }
    val trimFeatures: String? = if (exclusive) null else if (features.isBlank()) "'halt' 1" else "$features, 'halt' 1"

    fun type(char: Char): HanPunctuation {
        val known = hanPunctuation(char)
        if (known != HanPunctuation.FONT_DEPENDENT) return known
        return types.getOrPut(char) {
            val value = char.toString()
            val bounds = Rect()
            paint.getTextBounds(value, 0, 1, bounds)
            val half = paint.measureText(value) / 2f
            val kind = when {
                bounds.isEmpty -> HanPunctuation.OTHER
                bounds.right <= half -> HanPunctuation.CLOSE
                bounds.left >= half -> HanPunctuation.OPEN
                bounds.width() <= half && bounds.left >= half / 2f -> HanPunctuation.MIDDLE
                else -> HanPunctuation.OTHER
            }
            when (char) {
                '\u2018', '\u201c' -> if (kind == HanPunctuation.OPEN) kind else HanPunctuation.OPEN_NARROW
                '\u2019', '\u201d' -> if (kind == HanPunctuation.CLOSE) kind else HanPunctuation.CLOSE_NARROW
                else -> kind
            }
        }
    }

    companion object {
        private val EXCLUSIVE = setOf("halt", "hwid", "palt", "pwid", "qwid", "twid", "valt", "vhal", "vpal")
        private val FEATURE = Regex("""['"]?([a-zA-Z0-9]{4})['"]?(?:\s+(on|off|\d+))?""")
    }
}
