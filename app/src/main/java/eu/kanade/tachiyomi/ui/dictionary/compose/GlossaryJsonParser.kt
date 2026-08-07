package eu.kanade.tachiyomi.ui.dictionary.compose

import org.json.JSONArray
import org.json.JSONObject
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
internal fun parseStructuredGlossary(raw: String): StructuredEntry {
    val trimmed = raw.trim()
    if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
        val tree = runCatching {
            val nodes = if (trimmed.startsWith("[")) {
                parseStructuredArray(JSONArray(trimmed))
            } else {
                parseStructuredValue(JSONObject(trimmed))
            }
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

private fun parseStructuredArray(array: JSONArray): List<StructuredNode> {
    val out = mutableListOf<StructuredNode>()
    var i = 0
    while (i < array.length()) {
        val item = array.opt(i)
        when (item) {
            is JSONObject -> out.addAll(parseStructuredValue(item))
            is JSONArray -> out.addAll(parseStructuredArray(item))
            is String -> if (item.isNotEmpty()) out.add(StructuredNode.Text(item))
            is Number, is Boolean -> out.add(StructuredNode.Text(item.toString()))
            else -> {
            }
        }
        i++
    }
    return out
}

private fun parseStructuredValue(value: Any?): List<StructuredNode> = when (value) {
    null -> emptyList()
    is JSONObject -> listOfNotNull(parseStructuredObject(value))
    is JSONArray -> parseStructuredArray(value)
    is String -> if (value.isEmpty()) emptyList() else listOf(StructuredNode.Text(value))
    is Number, is Boolean -> listOf(StructuredNode.Text(value.toString()))
    else -> emptyList()
}

private fun parseStructuredObject(item: JSONObject): StructuredNode? {
    if (item.length() == 0) return null

    // structured-content wrapper with nested content — children are inlined into the parent list
    if (item.optString("type") == "structured-content") {
        val children = parseStructuredValue(item.opt("content"))
        return when {
            children.isEmpty() -> null
            children.size == 1 -> children[0]
            else -> StructuredNode.Element(StructuredTag.Span, children = children)
        }
    }

    if (item.optString("type") == "image") {
        return StructuredNode.Element(
            StructuredTag.Image,
            attributes = StructuredAttributes(
                style = parseStyleMap(item.optJSONObject("style")),
                data = parseDataMap(item.optJSONObject("data")),
                properties = buildOfNotNull(
                    "path" to item.optString("path", item.optString("src", "")).takeIf { it.isNotEmpty() },
                    "title" to item.optString("title").takeIf { it.isNotEmpty() },
                ),
            ),
        )
    }

    // text node: {"text": "..."} or {"text": [..., ...]} with optional style
    if (item.has("text")) {
        return StructuredNode.Element(
            StructuredTag.Span,
            children = parseStructuredValue(item.opt("text")),
            attributes = StructuredAttributes(style = parseStyleMap(item.optJSONObject("style"))),
        )
    }

    // ruby: {"ruby": {"text": [...], "rt": [...]}}
    if (item.has("ruby")) {
        val ruby = item.optJSONObject("ruby")
        val base = ruby?.opt("text")
        val rt = ruby?.opt("rt")
        val baseText = parseStructuredValue(base).joinToString("") { it.collectText() }
        val rtText = parseStructuredValue(rt).joinToString("") { it.collectText() }
        val children = buildList {
            if (baseText.isNotBlank()) add(StructuredNode.Text(baseText))
            if (rtText.isNotBlank()) add(StructuredNode.Element(StructuredTag.Rt, children = listOf(StructuredNode.Text(rtText))))
        }
        return StructuredNode.Element(StructuredTag.Ruby, children = children)
    }

    val tag = item.optString("tag", "").trim().lowercase()
    val structuredTag = if (tag.equals("img", true)) StructuredTag.Image else StructuredTag.fromRaw(tag)

    // `{"type": "image"}` and `{"tag": "img"}` are equivalent (renderer.js maps the former to
    // the latter). Capture the image's path/sizing/rendering properties in both cases.
    val isImage = structuredTag == StructuredTag.Image
    return StructuredNode.Element(
        structuredTag,
        children = parseStructuredValue(item.opt("content")),
        attributes = StructuredAttributes(
            data = parseDataMap(item.optJSONObject("data")),
            style = parseStyleMap(item.optJSONObject("style")),
            properties = buildOfNotNull(
                "href" to item.optString("href").takeIf { it.isNotEmpty() },
                "title" to item.optString("title").takeIf { it.isNotEmpty() },
                "lang" to item.optString("lang").takeIf { it.isNotEmpty() },
                "colSpan" to (item.opt("colSpan") as? Number)?.toString(),
                "rowSpan" to (item.opt("rowSpan") as? Number)?.toString(),
                "open" to item.optBoolean("open").takeIf { it }?.toString(),
            ).toMutableMap().apply {
                if (isImage) {
                    item.optString("path", item.optString("src", "")).takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    (item.opt("width") as? Number)?.toString()?.let { put("width", it) }
                    (item.opt("height") as? Number)?.toString()?.let { put("height", it) }
                    item.optString("sizeUnits").takeIf { it.isNotEmpty() }?.let { put("sizeUnits", it) }
                    item.optString("verticalAlign").takeIf { it.isNotEmpty() }?.let { put("verticalAlign", it) }
                    item.optString("appearance").takeIf { it.isNotEmpty() }?.let { put("appearance", it) }
                    item.optBoolean("background").takeIf { it }?.toString()?.let { put("background", it) }
                    item.optBoolean("collapsed").takeIf { it }?.toString()?.let { put("collapsed", it) }
                }
            },
        ),
    )
}

private fun StructuredNode.collectText(): String = when (this) {
    is StructuredNode.Text -> text
    is StructuredNode.Element -> children.joinToString("") { it.collectText() }
    StructuredNode.TextBreak -> "\n"
}

private fun parseDataMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, String>()
    obj.keys().forEach { key ->
        val value = obj.opt(key)
        if (!JSONObject.NULL.equals(value) && value != null) out[key] = value.toString()
    }
    return out
}

/** Parses `{"color": "red", "fontWeight": "bold", ...}` style object into camel-case props. */
private fun parseStyleMap(obj: JSONObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, String>()
    obj.keys().forEach { key ->
        val value = obj.opt(key)
        if (!JSONObject.NULL.equals(value) && value != null) {
            val str = value.toString()
            if (str.isNotEmpty()) out[toCamelCase(key)] = str
        }
    }
    return out
}

private fun buildOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for ((k, v) in pairs) if (v != null) out[k] = v
    return out
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
            if (text.isNotBlank()) out.add(StructuredNode.Text(text))
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