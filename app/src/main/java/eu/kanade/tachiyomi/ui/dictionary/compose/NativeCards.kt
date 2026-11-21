package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.Frequency
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the `GRP1` blob produced by `HoshiDicts.cards`: expression/reading/rules,
 * process steps, dictionary groups with glossaries, frequencies and pitches. Group order
 * is resolved here (it needs the localized titles) and stays stable.
 */
internal fun parseCards(bytes: ByteArray, resolveTitle: (String) -> String,
    priority: Map<String, Int>, results: List<LookupResult>): List<TermCard> {
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check(header.int == 0x47525031) { "Invalid card blob" }
    val cardCount = header.int
    val poolBytes = header.int
    val pool = ByteArray(poolBytes)
    header.get(pool)
    val records = header

    fun text(): String = String(pool, records.int, records.int, Charsets.UTF_8)

    val cards = ArrayList<TermCard>(cardCount)
    repeat(cardCount) {
        val expression = text()
        val reading = text()
        val matched = text()
        val deinflected = text()
        val termTags = text()
        val rules = text()

        val processCount = records.int
        val process = ArrayList<ProcessStep>(processCount)
        repeat(processCount) { process.add(ProcessStep(text(), text())) }

        val groupCount = records.int
        val groups = ArrayList<DictionaryGroup>(groupCount)
        repeat(groupCount) {
            val dictName = text()
            val glossCount = records.int
            val glosses = ArrayList<GlossaryEntry>(glossCount)
            repeat(glossCount) {
                glosses.add(GlossaryEntry(dictName, text(), text(), text()))
            }
            val title = resolveTitle(dictName)
            groups.add(DictionaryGroup(dictName, title, glosses,
                glosses.map { nativeCoreSource(it.glossary) }))
        }
        groups.sortBy { priority[it.dictName] ?: Int.MAX_VALUE }

        val frequencyCount = records.int
        val frequencies = ArrayList<FrequencyEntry>(frequencyCount)
        repeat(frequencyCount) {
            val dictName = text()
            val valueCount = records.int
            val values = ArrayList<Frequency>(valueCount)
            repeat(valueCount) { values.add(Frequency(records.int, text())) }
            frequencies.add(FrequencyEntry(dictName, values.toTypedArray()))
        }

        val pitchCount = records.int
        val pitches = ArrayList<PitchEntry>(pitchCount)
        repeat(pitchCount) {
            val dictName = text()
            val positionCount = records.int
            val positions = IntArray(positionCount) { records.int }
            pitches.add(PitchEntry(dictName, positions))
        }

        cards.add(TermCard(expression, reading, matched, deinflected, termTags, rules,
            process, groups, frequencies, pitches))
    }
    return if (results.isEmpty()) cards else assignResultIndices(cards, results)
}

/**
 * Native cards are grouped by expression and reading, and the C++ grouping walks the session's
 * results rather than the display-ordered list. Map each card back to the result the renderer
 * shows so Anki exports the same term and glossaries as the WebView, then restore the display
 * order so the native card list matches the WebView entries.
 */
private fun assignResultIndices(cards: List<TermCard>, results: List<LookupResult>): List<TermCard> {
    val used = BooleanArray(results.size)
    var complete = true
    val assigned = cards.mapIndexed { cardIndex, card ->
        fun find(match: (LookupResult) -> Boolean): Int = results.indices.firstOrNull { index ->
            !used[index] && match(results[index])
        } ?: -1
        val exact = find { result ->
            result.term.expression == card.expression &&
                result.term.reading == card.reading &&
                result.matched == card.matched &&
                result.deinflected == card.deinflected &&
                result.term.glossaries.map { it.dictName }.toSet() ==
                card.dictGroups.map { it.dictName }.toSet()
        }
        val relaxed = if (exact >= 0) exact else find { result ->
            result.term.expression == card.expression && result.term.reading == card.reading
        }
        val index = if (relaxed >= 0) {
            used[relaxed] = true
            relaxed
        } else {
            complete = false
            cardIndex.coerceAtMost(results.lastIndex.coerceAtLeast(0))
        }
        card.copy(resultIndex = index)
    }
    return if (complete) assigned.sortedBy { it.resultIndex } else assigned
}
