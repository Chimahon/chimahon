package eu.kanade.tachiyomi.ui.dictionary.compose

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Parses a single dictionary glossary string into a [StructuredEntry].
 *
 * Dictionaries store glosses as structured-JSON ("structured-content"), plain text, or legacy
 * HTML. This mirrors the WebView's `renderStructuredObjectNode` / `appendStructured` walk,
 * preserving every element tag, `data` attribute, inline `style`, and link so that dictionary
 * CSS (via [ParsedCss]) can be applied in the Compose renderer.
 */
public fun parseStructuredGlossary(raw: String): StructuredEntry {
    val trimmed = raw.trim()
    if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
        val tree = runCatching {
            val nodes = GlossaryJsonReader(trimmed).read()
            nodes.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (tree != null) return StructuredEntry.Tree(tree)
    }
    if (trimmed.contains('<')) {
        val htmlNodes = runCatching { parseHtmlRoot(trimmed) }.getOrNull()
        if (!htmlNodes.isNullOrEmpty()) return StructuredEntry.Tree(htmlNodes)
    }
    return StructuredEntry.PlainText
}

private fun buildOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for ((k, v) in pairs) if (v != null) out[k] = v
    return out
}

/**
 * A plain-text gloss keeps its line structure.
 *
 * The reference renders plain text with `white-space: pre-wrap`: a newline is a line break and a
 * blank line is a blank line. Structured content is the opposite - the browser collapses a
 * newline inside a text node to a space - so the newline has to become an explicit break here
 * rather than text the CSS white-space processing can collapse.
 */
public fun plainTextNodes(raw: String): List<StructuredNode> {
    val out = ArrayList<StructuredNode>()
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    for ((index, line) in lines.withIndex()) {
        if (index > 0) out.add(StructuredNode.TextBreak)
        if (line.isNotEmpty()) out.add(StructuredNode.Text(line))
    }
    return listOf(StructuredNode.Element(StructuredTag.Span, out,
        StructuredAttributes(style = mapOf("whiteSpace" to "pre-wrap"))))
}

// ---------------------------------------------------------------------------
// Legacy HTML (jsoup) fallback — used only when a gloss is HTML, not JSON.
// ---------------------------------------------------------------------------

private val BLOCK_TAGS = setOf(
    "ul", "ol", "li", "div", "p", "table", "tr", "td", "th", "section", "dt", "dd",
    "h1", "h2", "h3", "h4", "thead", "tbody", "tfoot",
)

private fun parseHtmlRoot(html: String): List<StructuredNode> {
    val doc = Jsoup.parseBodyFragment(html)
    val out = mutableListOf<StructuredNode>()
    for (child in doc.body()?.children() ?: emptyList()) {
        walkHtml(child, out)
    }
    while (out.isNotEmpty() && out.last() == StructuredNode.TextBreak) out.removeAt(out.size - 1)
    return out
}

private fun walkHtml(node: Node, out: MutableList<StructuredNode>) {
    when (node) {
        is TextNode -> {
            val text = node.text()
            // Keep whitespace-only nodes as a single space — dropping them glues adjacent
            // words: <b>foo</b> <i>bar</i> rendered "foobar".
            when {
                text.isNotEmpty() -> out.add(StructuredNode.Text(text))
                node.isBlank() -> out.add(StructuredNode.Text(" "))
            }
        }
        is Element -> {
            val tag = node.tagName().lowercase()
            if (tag == "br") {
                out.add(StructuredNode.TextBreak)
                return
            }
            if (tag == "img") {
                out.add(
                    StructuredNode.Element(
                        StructuredTag.Image,
                        attributes = StructuredAttributes(
                            properties = buildOfNotNull(
                                "path" to node.attr("src").takeIf { it.isNotEmpty() },
                                "title" to node.attr("title").takeIf { it.isNotEmpty() },
                            ),
                        ),
                    ),
                )
                return
            }
            if (tag == "ruby") {
                val rt = node.selectFirst("rt")
                val reading = rt?.ownText().orEmpty()
                val baseText = buildString {
                    node.childNodes().forEach { child ->
                        if (child is TextNode) append(child.text())
                        else if (child is Element && child.tagName() != "rt" && child.tagName() != "rp") append(child.ownText())
                    }
                    if (isBlank()) append(node.ownText())
                }
                val children = buildList {
                    if (baseText.isNotBlank()) add(StructuredNode.Text(baseText))
                    if (reading.isNotBlank()) add(StructuredNode.Element(StructuredTag.Rt, children = listOf(StructuredNode.Text(reading))))
                }
                out.add(StructuredNode.Element(StructuredTag.Ruby, children = children))
                return
            }

            val block = tag in BLOCK_TAGS
            if (block && out.isNotEmpty() && out.last() != StructuredNode.TextBreak) {
                out.add(StructuredNode.TextBreak)
            }
            val children = mutableListOf<StructuredNode>()
            node.childNodes().forEach { walkHtml(it, children) }
            if (children.isEmpty()) return
            if (block && children.last() != StructuredNode.TextBreak) {
                children.add(StructuredNode.TextBreak)
            }
            out.add(
                StructuredNode.Element(
                    StructuredTag.fromRaw(tag),
                    children = children,
                    attributes = StructuredAttributes(
                        style = parseInlineCssStyle(node.attr("style")),
                        properties = buildOfNotNull(
                            "href" to node.attr("href").takeIf { it.isNotEmpty() },
                            "title" to node.attr("title").takeIf { it.isNotEmpty() },
                        ),
                    ),
                ),
            )
        }
    }
}

/** Parses a legacy inline HTML `style="..."` into camel-case props (subset used by dicts). */
private fun parseInlineCssStyle(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val out = mutableMapOf<String, String>()
    for (decl in raw.split(";")) {
        val parts = decl.split(":", limit = 2)
        if (parts.size != 2) continue
        val key = parts[0].trim().lowercase()
        val value = parts[1].trim()
        if (key.isNotEmpty() && value.isNotEmpty()) out[toCamelCase(key)] = value
    }
    return out
}
