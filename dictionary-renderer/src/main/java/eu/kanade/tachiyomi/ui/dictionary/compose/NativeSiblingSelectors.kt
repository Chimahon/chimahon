package eu.kanade.tachiyomi.ui.dictionary.compose

import java.util.BitSet

/** Stylesheet-owned dispatch; bit positions retain the existing sibling-rule cascade order. */
internal class NativeSiblingSelectors(val rules: List<SiblingRule>) {
    private class Attributes {
        private val presence = HashMap<String, BitSet>()
        private val values = HashMap<String, BitSet>()

        fun add(name: String?, value: String?, rank: Int) {
            if (name != null) presence.getOrPut(name) { BitSet() }.set(rank)
            else if (value != null) values.getOrPut(value) { BitSet() }.set(rank)
        }

        fun match(attributes: StructuredAttributes, into: BitSet) {
            into.clear()
            if (presence.isNotEmpty()) for (key in attributes.data.keys) presence[key]?.let(into::or)
            if (values.isNotEmpty()) for (key in attributes.cssKeys.values) values[key]?.let(into::or)
        }
    }

    private val left = Attributes()
    private val right = Attributes()
    val hasGeneral = rules.any { !it.adjacent }

    init {
        for ((rank, rule) in rules.withIndex()) {
            left.add(rule.prevPresence, rule.prevKey, rank)
            right.add(rule.thisPresence, rule.thisKey, rank)
        }
    }

    fun preceding(attributes: StructuredAttributes, into: BitSet) = left.match(attributes, into)
    fun targets(attributes: StructuredAttributes, into: BitSet) = right.match(attributes, into)
}

/** Parent-local history stores matched rule ids, not copies of every preceding attribute. */
internal class NativeCssSiblings(private val selectors: NativeSiblingSelectors) {
    private val previous = BitSet()
    private val earlier = if (selectors.hasGeneral) BitSet() else null

    fun append(attributes: StructuredAttributes) {
        selectors.preceding(attributes, previous)
        earlier?.or(previous)
    }

    fun candidates(attributes: StructuredAttributes, into: BitSet): BitSet {
        selectors.targets(attributes, into)
        var rank = into.nextSetBit(0)
        while (rank >= 0) {
            val next = into.nextSetBit(rank + 1)
            val matched = if (selectors.rules[rank].adjacent) previous[rank] else checkNotNull(earlier)[rank]
            if (!matched) into.clear(rank)
            rank = next
        }
        return into
    }
}
