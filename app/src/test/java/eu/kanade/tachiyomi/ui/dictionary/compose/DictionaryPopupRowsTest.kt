package eu.kanade.tachiyomi.ui.dictionary.compose

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DictionaryPopupRowsTest {
    @Test fun defaultVisibilityReturnsTheWorkerOwnedList() {
        val header = DictionaryPopupRow(DictionaryPopupRowKind.TermHeader, 0, -1, -1, "header")
        val open = DictionaryPopupRow(DictionaryPopupRowKind.Glossary, 0, 0, 0, "open", "A", true)
        val closed = DictionaryPopupRow(DictionaryPopupRowKind.Glossary, 0, 1, 0, "closed", "B", false)
        val rows = DictionaryPopupRows(listOf(header, open, closed), listOf(header, open))
        assertSame(rows.initial, rows.visible(listOf(emptyMap())))
        assertEquals(listOf(header, closed), rows.visible(listOf(mapOf("A" to false, "B" to true))))
        assertSame(rows.initial, rows.visible(listOf(emptyMap())))
    }

    @Test fun overridesArePerCardAndNeverRemoveHeadersOrFooters() {
        val header = DictionaryPopupRow(DictionaryPopupRowKind.DictionaryHeader, 0, 0, -1, "header")
        val first = DictionaryPopupRow(DictionaryPopupRowKind.Glossary, 0, 0, 0, "first", "same", true)
        val second = DictionaryPopupRow(DictionaryPopupRowKind.Glossary, 1, 0, 0, "second", "same", true)
        val footer = DictionaryPopupRow(DictionaryPopupRowKind.Footer, 0, -1, -1, "footer")
        val all = listOf(header, first, second, footer)
        assertEquals(listOf(header, second, footer), DictionaryPopupRows(all, all)
            .visible(listOf(mapOf("same" to false), emptyMap())))
    }

    @Test fun preparationKeepsOriginalSlotKeysOrderAndCollapsedContent() {
        val card = mockk<TermCard>()
        val group = mockk<DictionaryGroup>()
        every { card.dictGroups } returns listOf(group)
        every { group.glosses } returns listOf(mockk(), mockk())
        every { group.title } returns "dict"
        val presentation = TermCardPresentation(emptySet(), listOf(GlossaryPresentation(
            ParsedCss.EMPTY, 200, false, listOf(emptyList(), emptyList()))))
        val rows = DictionaryPopupRows.prepare(listOf(card), listOf(presentation))
        assertEquals(listOf("term-header-0", "term-slot-0-dict-slot-0-header",
            "term-slot-0-dict-slot-0-gloss-0", "term-slot-0-dict-slot-0-gloss-1", "term-slot-0-footer"), rows.all.map { it.key })
        assertEquals(listOf(rows.all[0], rows.all[1], rows.all[4]), rows.initial)
        assertEquals(rows.all, rows.visible(listOf(mapOf("dict" to true))))
        assertEquals(rows.all.size, rows.all.map { it.key }.toSet().size)
    }
}
