package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HanPunctuationSpacingTest {
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
