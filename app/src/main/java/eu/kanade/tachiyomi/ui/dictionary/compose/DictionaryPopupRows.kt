package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.Immutable

internal enum class DictionaryPopupRowKind(val contentType: String) {
    TermHeader("term-header"), DictionaryHeader("dictionary-header"), Glossary("gloss"), Footer("term-footer"),
}

@Immutable
internal class DictionaryPopupRow(val kind: DictionaryPopupRowKind, val card: Int, val group: Int,
    val gloss: Int, val key: String, val groupTitle: String = "", val initiallyExpanded: Boolean = true)

/** Built on the preparation worker. Opening a popup does not register every source group again. */
@Immutable
internal class DictionaryPopupRows(val all: List<DictionaryPopupRow>, val initial: List<DictionaryPopupRow>) {
    fun visible(overrides: List<Map<String, Boolean>>): List<DictionaryPopupRow> {
        if (overrides.all { it.isEmpty() }) return initial
        return all.filter { it.kind != DictionaryPopupRowKind.Glossary ||
            (overrides[it.card][it.groupTitle] ?: it.initiallyExpanded) }
    }

    companion object {
        val Empty = DictionaryPopupRows(emptyList(), emptyList())

        fun prepare(cards: List<TermCard>, presentation: List<TermCardPresentation>): DictionaryPopupRows {
            val all = ArrayList<DictionaryPopupRow>()
            val initial = ArrayList<DictionaryPopupRow>()
            fun add(row: DictionaryPopupRow) {
                all.add(row)
                if (row.kind != DictionaryPopupRowKind.Glossary || row.initiallyExpanded) initial.add(row)
            }
            for ((cardIndex, card) in cards.withIndex()) {
                add(DictionaryPopupRow(DictionaryPopupRowKind.TermHeader, cardIndex, -1, -1, "term-header-$cardIndex"))
                for ((groupIndex, group) in card.dictGroups.withIndex()) {
                    val key = "term-slot-$cardIndex-dict-slot-$groupIndex"
                    val expanded = presentation[cardIndex].groups[groupIndex].initiallyExpanded
                    add(DictionaryPopupRow(DictionaryPopupRowKind.DictionaryHeader, cardIndex, groupIndex, -1, "$key-header"))
                    for (glossIndex in group.glosses.indices) add(DictionaryPopupRow(DictionaryPopupRowKind.Glossary,
                        cardIndex, groupIndex, glossIndex, "$key-gloss-$glossIndex", group.title, expanded))
                }
                add(DictionaryPopupRow(DictionaryPopupRowKind.Footer, cardIndex, -1, -1, "term-slot-$cardIndex-footer"))
            }
            return DictionaryPopupRows(all, initial)
        }
    }
}
