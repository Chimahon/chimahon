package eu.kanade.tachiyomi.ui.dictionary.compose

/** CSS Text whitespace processing shared across adjacent text nodes. */
internal class CssWhiteSpace {
    private var pending = false
    private var lineStart = true

    fun append(value: String, preserve: Boolean = false): String {
        if (value.isEmpty()) return value
        if (preserve || value.none(::isCssWhiteSpace)) {
            val prefix = takePending()
            lineStart = value.last() == '\n'
            return if (prefix) " $value" else value
        }
        val out = StringBuilder(value.length)
        for ((index, char) in value.withIndex()) {
            when {
                char == '\n' -> {
                    pending = false
                    // renderer.js emits explicit breaks; keep its established leading-break rule.
                    if (index != 0 || !lineStart) out.append(char)
                    lineStart = true
                }
                isCssWhiteSpace(char) -> pending = true
                else -> {
                    if (takePending()) out.append(' ')
                    out.append(char)
                    lineStart = false
                }
            }
        }
        return out.toString()
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
