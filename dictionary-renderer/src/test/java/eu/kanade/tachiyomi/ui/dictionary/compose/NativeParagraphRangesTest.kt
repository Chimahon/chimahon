package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.Spanned
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

class NativeParagraphRangesTest {
    @Test fun indexedQueriesMatchOrderedRangeQueries() {
        val random = Random(7218)
        repeat(100) {
            val spans = ArrayList<ParagraphSpan>()
            var offset = 0
            repeat(40) { index ->
                offset += random.nextInt(3)
                val end = offset + random.nextInt(1, 5)
                spans.add(ParagraphSpan(index.toString(), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE))
                spans.add(ParagraphSpan(index, offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE))
                offset = end
            }
            val ranges = NativeParagraphRangeIndex(spans, String::class.java)
            repeat(100) {
                val start = random.nextInt(offset + 2)
                val end = start + random.nextInt(1, 6)
                val matches = spans.filter { it.value is String && it.end > start && it.start < end }
                assertEquals(matches.firstOrNull()?.value, ranges.overlapping(start, end))
                assertEquals(matches.lastOrNull()?.value, ranges.lastOverlapping(start, end))
                assertEquals(spans.firstOrNull { it.value is String && it.start <= start && it.end > start }?.value,
                    ranges.at(start))
            }
        }
    }

    @Test fun adjacentRunsDoNotOverlapAtAnExclusiveBoundary() {
        val ranges = NativeParagraphRangeIndex(listOf(
            ParagraphSpan("a", 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
            ParagraphSpan("b", 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
        ), String::class.java)
        assertEquals("a", ranges.overlapping(1, 2))
        assertEquals("b", ranges.overlapping(2, 3))
        assertNull(ranges.at(4))
        assertEquals("b", ranges.lastOverlapping(1, 3))
    }

    @Test fun unrelatedOverlappingSpansAreNotPartOfTheTypedIndex() {
        val ranges = NativeParagraphRangeIndex(listOf(
            ParagraphSpan(1, 0, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
            ParagraphSpan("a", 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
            ParagraphSpan(2, 0, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
        ), String::class.java)
        assertEquals("a", ranges.at(3))
        assertNull(ranges.at(5))
    }

    @Test fun overlappingOrOutOfOrderRunsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeParagraphRangeIndex(listOf(
                ParagraphSpan("a", 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
                ParagraphSpan("b", 2, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE),
            ), String::class.java)
        }
    }
}
