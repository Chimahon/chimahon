package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.GetChars
import java.lang.reflect.Array as ReflectArray
import java.util.IdentityHashMap

/** Append-only paragraph input. Android's span tree is built only after text assembly finishes. */
internal class NativeParagraphText : CharSequence {
    private val characters = StringBuilder()
    private var spans: ArrayList<ParagraphSpan>? = null
    override val length: Int get() = characters.length
    override fun get(index: Int): Char = characters[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        characters.substring(startIndex, endIndex)
    override fun toString(): String = characters.toString()

    fun append(value: String) { characters.append(value) }
    fun trimLastCharacter() {
        val end = length - 1
        characters.setLength(end)
        val active = spans ?: return
        for (index in active.lastIndex downTo 0) {
            val span = active[index]
            if (span.start >= end) active.removeAt(index)
            else if (span.end > end) active[index] = span.copy(end = end)
        }
    }

    fun addSpan(value: Any, start: Int, end: Int, flags: Int) {
        require(start in 0..end && end <= length)
        require(flags == Spanned.SPAN_EXCLUSIVE_EXCLUSIVE || flags == Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        if (start == end) return
        val target = spans ?: ArrayList<ParagraphSpan>().also { spans = it }
        target.add(ParagraphSpan(value, start, end, flags))
    }

    fun hasLookupText(start: Int, end: Int): Boolean {
        var from = start
        // No-lookup ranges are appended in source order and never overlap another append.
        for (span in spans.orEmpty()) {
            if (span.value !is NativeNoLookup || span.end <= from) continue
            for (index in from until minOf(end, span.start)) if (!this[index].isWhitespace()) return true
            from = maxOf(from, span.end)
            if (from >= end) return false
        }
        for (index in from until end) if (!this[index].isWhitespace()) return true
        return false
    }

    fun ranges(): NativeParagraphRanges = spans?.let(::NativeParagraphRanges) ?: NativeParagraphRanges.EMPTY

    internal fun snapshot(): CharSequence {
        val value = characters.toString()
        val active = spans ?: return value
        return if (active.isEmpty()) value else ParagraphSpans(value, active.toList())
    }

    fun build(): CharSequence {
        val source = snapshot()
        return if (source is Spanned) SpannableStringBuilder(source) else source
    }
}

internal data class ParagraphSpan(val value: Any, val start: Int, val end: Int, val flags: Int)

/** Constructor-only source: the framework copies all spans then indexes them in one bulk pass. */
private class ParagraphSpans(private val characters: String, private val spans: List<ParagraphSpan>) :
    Spanned, GetChars, CharSequence by characters {
    private val positions = IdentityHashMap<Any, ParagraphSpan>(spans.size).apply {
        for (span in spans) put(span.value, span)
    }
    override fun toString(): String = characters
    override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) {
        characters.toCharArray(dest, destoff, start, end)
    }
    override fun getSpanStart(tag: Any?): Int = positions[tag]?.start ?: -1
    override fun getSpanEnd(tag: Any?): Int = positions[tag]?.end ?: -1
    override fun getSpanFlags(tag: Any?): Int = positions[tag]?.flags ?: 0
    override fun nextSpanTransition(start: Int, limit: Int, type: Class<*>?): Int {
        var next = limit
        for (span in spans) if (type == null || type.isInstance(span.value)) {
            if (span.start > start && span.start < next) next = span.start
            if (span.end > start && span.end < next) next = span.end
        }
        return next
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(start: Int, end: Int, type: Class<T>): Array<T> {
        val selected = spans.filter { type.isInstance(it.value) && inlineRangeOverlaps(it.start, it.end, start, end) }
        val result = ReflectArray.newInstance(type, selected.size) as Array<T>
        for (index in selected.indices) result[index] = selected[index].value as T
        return result
    }
}
