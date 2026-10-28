package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.Immutable

/** Mirrors renderer.js's sc-prefixed DOMStringMap key conversion, not CSS property camel-casing. */
internal fun structuredDataName(key: String): String? {
    if (key == "class") return key
    if (key.indices.any { key[it] == '-' && key.getOrNull(it + 1)?.let { next -> next in 'a'..'z' } == true }) return null
    if (key.isEmpty()) return key
    if (key[0] in 'a'..'z' && key.none { it in 'A'..'Z' }) return key
    return buildString {
        val name = key.take(1).uppercase() + key.substring(1)
        for (char in name) {
            if (char in 'A'..'Z') { if (isNotEmpty()) append('-'); append(char.lowercaseChar()) }
            else append(char)
        }
    }.removePrefix("-")
}

/**
 * A parsed structured-glossary element tree. Mirrors the DOM that `renderer.js` builds from
 * a dictionary's JSON `structured-content`, preserving every attribute needed to apply
 * dictionary CSS via `data-sc-content` / `data-sc-class` / other `data-*` values.
 */
@Immutable
sealed interface StructuredNode {
    @Immutable
    data object TextBreak : StructuredNode

    @Immutable
    data class Text(val text: String) : StructuredNode

    @Immutable
    data class Element(
        val tag: StructuredTag,
        val children: List<StructuredNode> = emptyList(),
        val attributes: StructuredAttributes = StructuredAttributes.Empty,
    ) : StructuredNode
}

enum class StructuredTag {
    Span, Div, Ruby, Rt, Rp, Table, Thead, Tbody, Tfoot, Tr, Td, Th,
    OrderedList, UnorderedList, ListItem, Details, Summary, Link, Image, Break,
    Bold, Italic, Underline, Strike, Superscript, Subscript, Small, Mark, HorizontalRule,
    Unknown;

    companion object {
        fun fromRaw(tag: String?): StructuredTag = when (tag?.lowercase()) {
            "span" -> Span
            "div" -> Div
            "ruby" -> Ruby
            "rt" -> Rt
            "rp" -> Rp
            "table" -> Table
            "thead" -> Thead
            "tbody" -> Tbody
            "tfoot" -> Tfoot
            "tr" -> Tr
            "td" -> Td
            "th" -> Th
            "ol" -> OrderedList
            "ul" -> UnorderedList
            "li" -> ListItem
            "details" -> Details
            "summary" -> Summary
            "a" -> Link
            "br" -> Break
            // Semantic inline tags — browser-default styles applied in StructuredElementView.
            "b", "strong" -> Bold
            "i", "em", "cite", "var", "dfn" -> Italic
            "u", "ins" -> Underline
            "s", "strike", "del" -> Strike
            "sup" -> Superscript
            "sub" -> Subscript
            "small" -> Small
            "mark" -> Mark
            "hr" -> HorizontalRule
            else -> Unknown
        }
    }

    /** Raw HTML tag name, for tag-scoped CSS matching (`& th`, `tr[x] th`). */
    val tagName: String
        get() = when (this) {
            Span -> "span"
            Div -> "div"
            Ruby -> "ruby"
            Rt -> "rt"
            Rp -> "rp"
            Table -> "table"
            Thead -> "thead"
            Tbody -> "tbody"
            Tfoot -> "tfoot"
            Tr -> "tr"
            Td -> "td"
            Th -> "th"
            OrderedList -> "ol"
            UnorderedList -> "ul"
            ListItem -> "li"
            Details -> "details"
            Summary -> "summary"
            Link -> "span"
            Image -> "img"
            Break -> "br"
            Bold -> "b"
            Italic -> "i"
            Underline -> "u"
            Strike -> "s"
            Superscript -> "sup"
            Subscript -> "sub"
            Small -> "small"
            Mark -> "mark"
            HorizontalRule -> "hr"
            Unknown -> ""
        }
}

/** Attributes of a structured element, preserving the raw JSON values. */
@Immutable
data class StructuredAttributes(
    val data: Map<String, String> = emptyMap(),
    val style: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
) {
    companion object {
        val Empty = StructuredAttributes()

        internal fun create(data: Map<String, String>, style: Map<String, String>, properties: Map<String, String>): StructuredAttributes =
            if (data.isEmpty() && style.isEmpty() && properties.isEmpty()) Empty else StructuredAttributes(data, style, properties)
    }

    internal val cssKeys = cssAttributeKeys(data)
    internal val nativeStyle = NativeCssDeclarations.compile(style)

    /** `data-sc-content` and `data-sc-class` values carried by this node. */
    val dataValues: List<String>
        get() {
            val out = mutableListOf<String>()
            data["content"]?.let { out.add(it) }
            data["class"]?.let { c -> out.addAll(c.split(" ").filter { it.isNotBlank() }) }
            return out
        }
}

/** A single parsed glossary line (entry), produced by [GlossaryJsonParser]. */
@Immutable
sealed interface StructuredEntry {
    @Immutable
    data class Tree(val nodes: List<StructuredNode>) : StructuredEntry
    @Immutable
    data object PlainText : StructuredEntry
}
