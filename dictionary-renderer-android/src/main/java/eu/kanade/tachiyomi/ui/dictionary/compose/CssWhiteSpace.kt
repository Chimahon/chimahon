package eu.kanade.tachiyomi.ui.dictionary.compose

/** CSS Text whitespace processing shared across adjacent text nodes. */
internal class CssWhiteSpace {
    private var pending = false
    private var lineStart = true

    fun append(value: String, preserve: Boolean = false): String =
        buildString(value.length) { appendTo(value, preserve, this) }

    fun appendTo(value: String, preserve: Boolean, out: Appendable) {
        if (value.isEmpty()) return
        if (preserve) {
            if (takePending()) out.append(' ')
            out.append(value)
            lineStart = value.last() == '\n'
            return
        }
        var runStart = 0
        for ((index, char) in value.withIndex()) {
            if (!isCssWhiteSpace(char)) continue
            if (runStart < index) {
                if (takePending()) out.append(' ')
                out.append(value, runStart, index)
                lineStart = false
            }
            if (char == '\n') {
                pending = false
                // Keep the established leading-break rule across adjacent source nodes.
                if (index != 0 || !lineStart) out.append(char)
                lineStart = true
            } else {
                pending = true
            }
            runStart = index + 1
        }
        if (runStart < value.length) {
            if (takePending()) out.append(' ')
            out.append(value, runStart, value.length)
            lineStart = false
        }
    }

    fun reset() {
        pending = false
        lineStart = true
    }

    fun markContent() { lineStart = false }

    private fun takePending(): Boolean {
        val emit = pending && !lineStart
        pending = false
        return emit
    }
}

internal fun isCssWhiteSpace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\u000C'
