package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.style.ReplacementSpan

/** Disjoint runs are emitted in source order, allowing queries without Android span arrays. */
internal class NativeParagraphRangeIndex<T : Any>(spans: List<ParagraphSpan>, private val type: Class<T>) {
    private val ranges = spans.filter { type.isInstance(it.value) }

    init {
        for (index in 1 until ranges.size) require(ranges[index - 1].end <= ranges[index].start)
    }

    fun at(offset: Int): T? = overlapping(offset, offset + 1)

    fun lastOverlapping(start: Int, end: Int): T? {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle].start < end) low = middle + 1 else high = middle
        }
        val range = ranges.getOrNull(low - 1) ?: return null
        return if (range.end > start) type.cast(range.value) else null
    }

    fun overlapping(start: Int, end: Int): T? {
        var low = 0
        var high = ranges.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ranges[middle].end <= start) low = middle + 1 else high = middle
        }
        val range = ranges.getOrNull(low) ?: return null
        return if (range.start < end) type.cast(range.value) else null
    }
}

/** Read-only finalization facts; later kerning/indent spans cannot change these run categories. */
internal class NativeParagraphRanges(spans: List<ParagraphSpan>) {
    val styles = NativeParagraphRangeIndex(spans, NativeStyleSpan::class.java)
    val replacements = NativeParagraphRangeIndex(spans, ReplacementSpan::class.java)
    val trims = NativeParagraphRangeIndex(spans, NativeTextSpacingTrim::class.java)

    companion object { val EMPTY = NativeParagraphRanges(emptyList()) }
}
