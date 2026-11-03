package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RubyTextPartsTest {
    @Test fun singleTextKeepsItsInputAndResetDoesNotChangeEarlierSnapshots() {
        val base = TextStyle(fontSize = 14.sp)
        val changed = base.copy(fontWeight = FontWeight.Bold)
        val parts = RubyTextParts(base)
        val value = "\u65e5\u672c\u8a9e"
        parts.append(value, changed)
        assertSame(value, parts.text)
        val runs = parts.runs()
        parts.append(" more", base)
        val text = parts.text.toString()
        parts.reset(changed)
        parts.append("fresh", changed)
        assertEquals(value + " more", text)
        assertEquals(listOf(RubyTextRun(0, value.length, changed)), runs)
        assertEquals(changed, parts.style)
        assertEquals(emptyList<RubyTextRun>(), parts.runs())
        parts.append(" joined", base)
        assertEquals("fresh joined", parts.text.toString())
    }

    @Test fun segmentsMatchThePreviousConcatenatingBuilderIncludingMixedAndEmptyRuns() {
        val base = TextStyle(fontSize = 14.sp)
        val styles = listOf(base, base.copy(fontWeight = FontWeight.Bold), base.copy(fontSize = 7.sp))
        val random = Random(42)
        repeat(300) {
            val parts = RubyTextParts(base)
            val old = ArrayList<Pair<String, TextStyle>>()
            val text = StringBuilder()
            repeat(random.nextInt(50)) {
                val value = listOf("", "a", "bc", "\u304b\u306a", " ", "\uD83D\uDE00")[random.nextInt(6)]
                val style = styles[random.nextInt(styles.size)]
                parts.append(value, style)
                if (value.isNotEmpty()) {
                    text.append(value)
                    val last = old.lastOrNull()
                    if (last?.second == style) old[old.lastIndex] = last.first + value to style
                    else old.add(value to style)
                }
                var offset = 0
                val expected = if (old.size == 1 && old[0].second == base) emptyList() else old.map { (value, style) ->
                    RubyTextRun(offset, offset + value.length, style).also { offset += value.length }
                }
                assertEquals(text.toString(), parts.text.toString())
                assertEquals(expected, parts.runs())
            }
            parts.clear()
            assertEquals("", parts.text.toString())
            assertEquals(emptyList<RubyTextRun>(), parts.runs())
            parts.append("fresh", base)
            assertEquals("fresh", parts.text.toString())
            assertEquals(emptyList<RubyTextRun>(), parts.runs())
        }
    }
}
