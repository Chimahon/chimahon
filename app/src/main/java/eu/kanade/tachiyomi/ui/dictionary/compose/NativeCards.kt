package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.Frequency
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.PitchEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the `GRP1` blob produced by `HoshiDicts.cards`: expression/reading/rules,
 * process steps, dictionary groups with glossaries, frequencies and pitches. Group order
 * is resolved here (it needs the localized titles) and stays stable.
 */
internal fun parseCards(bytes: ByteArray, resolveTitle: (String) -> String,
    priority: Map<String, Int>): List<TermCard> {
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
        groups.sortBy { priority[it.title] ?: Int.MAX_VALUE }

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
    return cards
}
