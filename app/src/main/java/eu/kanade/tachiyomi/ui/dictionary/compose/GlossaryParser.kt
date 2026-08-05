package eu.kanade.tachiyomi.ui.dictionary.compose

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** A single visual run inside a dictionary glossary. */
sealed interface GlossNode {
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val color: Long? = null,
    ) : GlossNode

    data class Ruby(val text: String, val ruby: String) : GlossNode
    data class Image(val uri: String?) : GlossNode
    object Break : GlossNode
    object Space : GlossNode
}

/**
 * Parse a single dictionary glossary string into renderable [GlossNode]s.
 *
 * Dictionaries can store glosses as plain text, HTML, or structured-JSON
 * "structured content". This is a bounded CSS/HTML/JSON subset — advanced
 * selectors, pseudo elements, media queries and animations are not replicated.
 * Every unsupported construct degrades gracefully to plain text.
 */
internal fun parseGlossary(raw: String): List<GlossNode> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return listOf(GlossNode.Run(""))
    if (trimmed.startsWith("[")) {
        parseStructured(trimmed)?.let { return it }
    } else if (trimmed.startsWith("{")) {
        parseStructuredObject(JSONObject(trimmed))?.let { return it }
    }
    if (trimmed.contains('<')) {
        parseHtml(trimmed)?.let { return it }
    }
    return listOf(GlossNode.Run(trimmed))
}

// ---------------------------------------------------------------------------
// Structured content (JSON)
// ---------------------------------------------------------------------------

private fun parseStructured(text: String): List<GlossNode>? =
    runCatching {
        val array = JSONArray(text)
        val out = mutableListOf<GlossNode>()
        for (i in 0 until array.length()) {
            val item = array.get(i)
            when (item) {
                is String -> out.add(GlossNode.Run(item))
                is Int -> out.add(GlossNode.Run(item.toString()))
                is Double -> out.add(GlossNode.Run(item.toString()))
                is Boolean -> out.add(GlossNode.Run(item.toString()))
                is JSONArray -> {
                    parseStructured(item.toString())?.let { out.addAll(it) }
                }
                is JSONObject -> {
                    val nodes = parseStructuredObject(item) ?: continue
                    if (out.isNotEmpty() && nodes.firstOrNull() is GlossNode.Run) {
                        out.add(GlossNode.Space)
                    }
                    out.addAll(nodes)
                }
            }
        }
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun parseStructuredObject(obj: JSONObject): List<GlossNode>? {
    // Plain text node
    if (obj.has("text")) {
        val text = obj.opt("text")
        val runs = when (text) {
            is String -> listOf(text)
            is JSONArray -> (0 until text.length()).mapNotNull { text.optString(it, "").takeIf { s -> s.isNotEmpty() } }
            is Int -> listOf(text.toString())
            is Double -> listOf(text.toString())
            else -> return null
        }
        return runs.map { GlossNode.Run(it) }
    }

    // Ruby
    if (obj.has("ruby")) {
        val ruby = obj.opt("ruby")
        val base = when (ruby) {
            is JSONObject -> ruby.optString("text", "")
            is JSONArray -> if (ruby.length() > 0) {
                val first = ruby.opt(0)
                when (first) {
                    is JSONObject -> first.optString("text", "")
                    is String -> first
                    else -> ""
                }
            } else ""
            else -> ""
        }
        val reading = when (ruby) {
            is JSONObject -> ruby.optString("rt", ruby.optString("reading", ""))
            is JSONArray -> if (ruby.length() > 0) {
                val first = ruby.opt(0)
                when (first) {
                    is JSONObject -> first.optString("rt", first.optString("reading", ""))
                    is String -> ""
                    else -> ""
                }
            } else ""
            else -> ""
        }
        if (base.isNotEmpty() && reading.isNotEmpty()) {
            return listOf(GlossNode.Ruby(base, reading))
        }
        if (base.isNotEmpty()) return listOf(GlossNode.Run(base))
        return null
    }

    // Image
    if (obj.has("image")) {
        val image = obj.optJSONObject("image")
        val path = image?.optString("path", "") ?: ""
        val uri = path.takeIf { it.isNotEmpty() }
        return listOf(GlossNode.Image(uri))
    }

    // Tagged content node: {"tag": "name", "content": [...]}
    if (obj.has("tag") || obj.has("content")) {
        val content = obj.opt("content")
        val children = when (content) {
            is JSONArray -> parseStructured(content.toString()) ?: emptyList()
            is JSONObject -> parseStructuredObject(content) ?: emptyList()
            is String -> listOf(GlossNode.Run(content))
            else -> emptyList()
        }
        if (children.isEmpty()) return children

        val tagName = when (val tag = obj.opt("tag")) {
            is String -> tag
            is JSONObject -> tag.optString("name", "")
            else -> ""
        }
        val isBlock = tagName in setOf("div", "p", "li", "h1", "h2", "h3", "h4", "table", "tr", "ul", "ol", "tabs", "tab")
        val bold = obj.optBoolean("bold", false) || tagName in setOf("strong", "b")
        val italic = obj.optBoolean("italic", false) || tagName in setOf("em", "i")
        val underline = obj.optBoolean("underline", false) || tagName == "u"

        val out = mutableListOf<GlossNode>()
        val needsLeadingBreak = isBlock && out.isEmpty()
        for (child in children) {
            if (out.isNotEmpty() && child is GlossNode.Run && out.last() is GlossNode.Run) {
                out.add(GlossNode.Space)
            }
            if (child is GlossNode.Run && (bold || italic || underline)) {
                out.add(child.copy(bold = child.bold || bold, italic = child.italic || italic, underline = child.underline || underline))
            } else {
                out.add(child)
            }
        }
        if (isBlock) out.add(GlossNode.Break)
        return out
    }

    // Table
    if (obj.has("table")) {
        val table = obj.optJSONArray("table") ?: return null
        val out = mutableListOf<GlossNode>()
        for (i in 0 until table.length()) {
            val row = table.optJSONArray(i) ?: continue
            for (j in 0 until row.length()) {
                val cell = row.opt(j)
                val nodes = when (cell) {
                    is JSONArray -> parseStructured(cell.toString()) ?: emptyList()
                    is JSONObject -> parseStructuredObject(cell) ?: emptyList()
                    is String -> listOf(GlossNode.Run(cell))
                    else -> emptyList()
                }
                if (j > 0 && nodes.isNotEmpty()) out.add(GlossNode.Space)
                out.addAll(nodes)
            }
            out.add(GlossNode.Break)
        }
        return out
    }

    return null
}

// ---------------------------------------------------------------------------
// HTML (jsoup)
// ---------------------------------------------------------------------------

private fun parseHtml(html: String): List<GlossNode>? =
    runCatching {
        val doc = Jsoup.parseBodyFragment(html)
        val out = mutableListOf<GlossNode>()
        for (child in doc.body()?.children() ?: emptyList()) {
            walkElement(child, out)
        }
        // strip trailing breaks
        while (out.isNotEmpty() && out.last() == GlossNode.Break) out.removeAt(out.size - 1)
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun walkElement(el: Element, out: MutableList<GlossNode>) {
    val tag = el.tagName()
    if (tag == "br") {
        out.add(GlossNode.Break)
        return
    }
    if (tag == "img") {
        val src = el.attr("src")
        out.add(GlossNode.Image(src.takeIf { it.isNotEmpty() }))
        return
    }
    if (tag == "ruby" || tag == "rt") {
        // handle <ruby>text<rt>reading</rt></ruby>
        val rt = el.selectFirst("rt")
        val base = el.ownText().takeIf { it.isNotBlank() } ?: ""
        val reading = rt?.ownText() ?: ""
        if (base.isNotEmpty() && reading.isNotEmpty()) {
            out.add(GlossNode.Ruby(base, reading))
            return
        }
    }

    val children = el.children()
    if (children.isEmpty()) {
        val text = el.ownText()
        if (text.isNotBlank()) {
            out.add(
                GlossNode.Run(
                    text = text,
                    bold = tag in setOf("b", "strong"),
                    italic = tag in setOf("i", "em"),
                    underline = tag == "u",
                ),
            )
        }
        return
    }

    if (tag in setOf("p", "div", "section", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "tr")) {
        out.add(GlossNode.Break)
    }
    val bold = tag in setOf("b", "strong")
    val italic = tag in setOf("i", "em")
    val underline = tag == "u"
    for (child in children) {
        val start = out.size
        walkElement(child, out)
        if (bold || italic || underline) {
            for (i in start until out.size) {
                val n = out[i]
                if (n is GlossNode.Run) {
                    out[i] = n.copy(
                        bold = n.bold || bold,
                        italic = n.italic || italic,
                        underline = n.underline || underline,
                    )
                }
            }
        }
        if (out.isNotEmpty() && out.last() !is GlossNode.Break && iHasBlock(child)) {
            out.add(GlossNode.Break)
        }
    }
}

private fun iHasBlock(el: Element): Boolean =
    el.tagName() in setOf("p", "div", "section", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table")
