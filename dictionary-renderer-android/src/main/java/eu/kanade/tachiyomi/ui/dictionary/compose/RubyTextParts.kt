package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.text.TextStyle

/** One shared text value and ordered style ranges, reusable within a document's lowering. */
internal class RubyTextParts(style: TextStyle) {
    var style = style
        private set
    private var first = ""
    private var joined: StringBuilder? = null
    private var joining = false
    val text: CharSequence get() = if (joining) checkNotNull(joined) else first
    private var currentStyle = style
    private var start = 0
    private var completed: ArrayList<RubyTextRun>? = null

    fun append(value: String, style: TextStyle) {
        if (value.isEmpty()) return
        if (currentStyle != style) {
            if (start < text.length) {
                val runs = completed ?: ArrayList<RubyTextRun>().also { completed = it }
                runs.add(RubyTextRun(start, text.length, currentStyle))
            }
            start = text.length
            currentStyle = style
        }
        if (text.isEmpty()) first = value else {
            val buffer = joined ?: StringBuilder().also { joined = it }
            if (!joining) {
                buffer.append(first)
                joining = true
            }
            buffer.append(value)
        }
    }

    fun runs(): List<RubyTextRun> {
        if (text.isEmpty() || completed.isNullOrEmpty() && currentStyle == style) return emptyList()
        return ArrayList<RubyTextRun>((completed?.size ?: 0) + 1).also {
            completed?.let(it::addAll)
            it.add(RubyTextRun(start, text.length, currentStyle))
        }
    }

    fun clear() {
        first = ""
        joined?.setLength(0)
        joining = false
        completed?.clear()
        currentStyle = style
        start = 0
    }

    fun reset(style: TextStyle) {
        clear()
        this.style = style
        currentStyle = style
    }
}
