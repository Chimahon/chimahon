package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CssWhiteSpaceTest {
    @Test fun preservesSpacesAcrossStyledRuns() {
        val spacing = CssWhiteSpace()
        assertEquals("first", spacing.append("first "))
        assertEquals(" second", spacing.append("second"))
        assertEquals("third", spacing.append("third"))
    }

    @Test fun onlyCssWhitespaceCollapses() {
        val spacing = CssWhiteSpace()
        assertEquals("a\u2009b\u3000c\u00a0d\u202fe", spacing.append("a\u2009b\u3000c\u00a0d\u202fe"))
        assertEquals(" f", spacing.append(" \t\r\u000Cf"))
    }

    @Test fun leadingAndTrailingSpacesDoNotLeakIntoTheNextBlock() {
        val spacing = CssWhiteSpace()
        assertEquals("one", spacing.append("  one  "))
        spacing.reset()
        assertEquals("two", spacing.append("two"))
    }

    @Test fun preWrapPreservesRunsAndLeadingSpaces() {
        val spacing = CssWhiteSpace()
        assertEquals("  a  \u2009b", spacing.append("  a  \u2009b", preserve = true))
    }

    @Test fun explicitBreaksResetPendingWhitespace() {
        val spacing = CssWhiteSpace()
        assertEquals("one", spacing.append("one "))
        spacing.reset()
        assertEquals("two", spacing.append(" two"))
    }
}
