package eu.kanade.tachiyomi.ui.dictionary.compose

/**
 * A parsed structured-glossary element tree. Mirrors the DOM that `renderer.js` builds from
 * a dictionary's JSON `structured-content`, preserving every attribute needed to apply
 * dictionary CSS via `data-sc-content` / `data-sc-class` / other `data-*` values.
 */
sealed interface StructuredNode {
    data object TextBreak : StructuredNode

    data class Text(val text: String) : StructuredNode

    data class Element(
        val tag: StructuredTag,
        val children: List<StructuredNode> = emptyList(),
        val attributes: StructuredAttributes = StructuredAttributes(),
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
}

/** Attributes of a structured element, preserving the raw JSON values. */
data class StructuredAttributes(
    val data: Map<String, String> = emptyMap(),
    val style: Map<String, String> = emptyMap(),
    val properties: Map<String, String> = emptyMap(),
) {
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
sealed interface StructuredEntry {
    data class Tree(val nodes: List<StructuredNode>) : StructuredEntry
    data object PlainText : StructuredEntry
}