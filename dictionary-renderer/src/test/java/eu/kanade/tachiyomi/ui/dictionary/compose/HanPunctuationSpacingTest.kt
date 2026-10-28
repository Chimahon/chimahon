package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HanPunctuationSpacingTest {
    @Test fun fastClassificationMatchesUnicodeCategoriesForEveryUtf16CodeUnit() {
        for (code in 0..0xffff) {
            val char = code.toChar()
            val fullwidth = char in '\u3000'..'\u303f' || char in '\uff01'..'\uff60'
            val expected = when (char) {
                '\u3001', '\u3002', '\uff0c', '\uff0e', '\uff1a', '\uff1b',
                '\u2018', '\u2019', '\u201c', '\u201d' -> HanPunctuation.FONT_DEPENDENT
                '\u00b7', '\u2027', '\u30fb', '\u3000' -> HanPunctuation.MIDDLE
                else -> when (Character.getType(char)) {
                    Character.START_PUNCTUATION.toInt() -> if (fullwidth) HanPunctuation.OPEN else HanPunctuation.OPEN_NARROW
                    Character.END_PUNCTUATION.toInt() -> if (fullwidth) HanPunctuation.CLOSE else HanPunctuation.CLOSE_NARROW
                    else -> HanPunctuation.OTHER
                }
            }
            assertEquals(expected, hanPunctuation(char), "U+${code.toString(16)}")
        }
    }

    @Test fun fullwidthAndNarrowBracketsStayDistinct() {
        for (char in "\u300c\u300e\u3010\uff08\uff3b") assertEquals(HanPunctuation.OPEN, hanPunctuation(char))
        for (char in "\u300d\u300f\u3011\uff09\uff3d") assertEquals(HanPunctuation.CLOSE, hanPunctuation(char))
        for (char in "(\uff62") assertEquals(HanPunctuation.OPEN_NARROW, hanPunctuation(char))
        for (char in ")\uff63") assertEquals(HanPunctuation.CLOSE_NARROW, hanPunctuation(char))
        assertEquals(HanPunctuation.OTHER, hanPunctuation('\u3042'))
        assertEquals(HanPunctuation.FONT_DEPENDENT, hanPunctuation('\u3002'))
    }

    @Test fun adjacentPairsSelectOnlyTheBlankHalfOfOneGlyph() {
        val open = HanPunctuation.OPEN
        val close = HanPunctuation.CLOSE
        val middle = HanPunctuation.MIDDLE
        assertEquals(0, hanKerningTarget(open, open))
        assertEquals(0, hanKerningTarget(close, open))
        assertEquals(0, hanKerningTarget(middle, open))
        assertEquals(-1, hanKerningTarget(close, close))
        assertEquals(-1, hanKerningTarget(close, middle))
        assertEquals(-1, hanKerningTarget(close, HanPunctuation.CLOSE_NARROW))
        assertEquals(0, hanKerningTarget(HanPunctuation.OPEN_NARROW, open))
        assertNull(hanKerningTarget(open, close))
        assertNull(hanKerningTarget(open, HanPunctuation.OTHER))
        assertNull(hanKerningTarget(HanPunctuation.CLOSE_NARROW, open))
    }
}
