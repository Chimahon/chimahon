package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.Spanned
import android.text.GetChars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class NativeParagraphTextTest {
    @Test fun finalizationSpansJoinTheSameSnapshotWithTheirOriginalFlags() {
        val text = NativeParagraphText()
        text.append("content")
        val initial = Any()
        val final = Any()
        text.addSpan(initial, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.addSpan(final, 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        val snapshot = text.snapshot() as Spanned
        assertEquals(listOf(initial, final), snapshot.getSpans(0, text.length, Any::class.java).toList())
        assertEquals(Spanned.SPAN_INCLUSIVE_EXCLUSIVE, snapshot.getSpanFlags(final))
    }

    @Test fun plainBuildKeepsAnImmutableStringInsteadOfAnAndroidSpanBuffer() {
        val text = NativeParagraphText()
        text.append("plain \u65e5\u672c\u8a9e")
        val built = text.build()
        assertTrue(built is String)
        text.append(" more")
        assertEquals("plain \u65e5\u672c\u8a9e", built)
    }

    @Test fun trimmingTheOnlySpanReturnsToThePlainRepresentation() {
        val text = NativeParagraphText()
        text.append("plain\n")
        text.addSpan(Any(), 5, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.trimLastCharacter()
        val built = text.build()
        assertTrue(built is String)
        assertEquals("plain", built)
    }

    @Test fun bulkCharacterCopyPreservesUtf16AndDestinationOffsets() {
        val value = "a\uD83C\uDDEF\uD83C\uDDF5\u65E5"
        val text = NativeParagraphText()
        text.append(value)
        text.addSpan(Any(), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val output = CharArray(value.length + 2)
        (text.snapshot() as GetChars).getChars(1, value.length, output, 2)
        assertEquals(value.substring(1), String(output, 2, value.length - 1))
    }

    @Test fun appendingAfterTrimmingCannotResurrectRemovedSpans() {
        val text = NativeParagraphText()
        val removed = Any()
        text.append("\n")
        text.addSpan(removed, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.trimLastCharacter()
        text.append("new\n")
        val content = Any()
        text.addSpan(content, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.trimLastCharacter()
        text.append(" tail")
        val snapshot = text.snapshot() as Spanned
        assertEquals(-1, snapshot.getSpanStart(removed))
        assertEquals(3, snapshot.getSpanEnd(content))
        assertEquals("new tail", snapshot.toString())
    }

    @Test fun laterAppendsDoNotMoveEarlierExclusiveSpans() {
        val text = NativeParagraphText()
        val first = Any()
        val second = Any()
        text.append("first")
        text.addSpan(first, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(" second")
        text.addSpan(second, 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val snapshot = text.snapshot() as Spanned
        assertEquals("first second", snapshot.toString())
        assertEquals(5, snapshot.getSpanEnd(first))
        assertEquals(6, snapshot.getSpanStart(second))
        assertSame(first, snapshot.getSpans(0, 12, Any::class.java)[0])
        assertEquals(5, snapshot.nextSpanTransition(0, 12, null))
        assertEquals(-1, snapshot.getSpanStart(Any()))
    }

    @Test fun trimmingTheTerminalBreakClipsSpansAndDropsEmptyExclusiveSpans() {
        val text = NativeParagraphText()
        val content = Any()
        val newline = Any()
        text.append("word\n")
        text.addSpan(content, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.addSpan(newline, 4, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.trimLastCharacter()
        val snapshot = text.snapshot() as Spanned
        assertEquals("word", snapshot.toString())
        assertEquals(4, snapshot.getSpanEnd(content))
        assertEquals(-1, snapshot.getSpanStart(newline))
        assertEquals(1, snapshot.getSpans(0, 4, Any::class.java).size)
    }

    @Test fun frozenSnapshotsDoNotTrackLaterAppends() {
        val text = NativeParagraphText()
        text.append("base")
        val first = text.snapshot()
        text.append(" more")
        assertEquals("base", first.toString())
        assertEquals("base more", text.snapshot().toString())
    }

    @Test fun linksIgnoreNoLookupTextButRetainOrdinaryContent() {
        val text = NativeParagraphText()
        text.append(" label ")
        text.addSpan(NativeNoLookup(), 1, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertFalse(text.hasLookupText(0, text.length))
        text.append("lookup")
        assertTrue(text.hasLookupText(0, text.length))
        assertFalse(text.hasLookupText(0, 7))
        assertTrue(text.hasLookupText(7, text.length))
    }

    @Test fun bulkSpanQueriesPreserveBoundaryTypeAndInsertionOrder() {
        val random = Random(2846)
        repeat(100) {
            val text = NativeParagraphText()
            val ranges = ArrayList<Triple<Any, Int, Int>>()
            repeat(30) { index ->
                val start = text.length
                text.append("part$index ")
                val tag = if (index % 2 == 0) NativeNoLookup() else Any()
                ranges.add(Triple(tag, start, text.length))
                text.addSpan(tag, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val snapshot = text.snapshot() as Spanned
            repeat(20) {
                val start = random.nextInt(text.length + 1)
                val end = random.nextInt(start, text.length + 1)
                val expected = ranges.filter { inlineRangeOverlaps(it.second, it.third, start, end) }
                assertEquals(expected.map { it.first }, snapshot.getSpans(start, end, Any::class.java).toList())
                assertEquals(expected.map { it.first }.filterIsInstance<NativeNoLookup>(),
                    snapshot.getSpans(start, end, NativeNoLookup::class.java).toList())
            }
        }
    }
}
