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
    data class ListMarker(val marker: String) : GlossNode
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
    parseStructured(text, ListContext())

private fun parseStructured(text: String, ctx: ListContext): List<GlossNode>? =
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
                    parseStructured(item.toString(), ctx)?.let { out.addAll(it) }
                }
                is JSONObject -> {
                    val nodes = parseStructuredObject(item, ctx) ?: continue
                    if (out.isNotEmpty() && nodes.firstOrNull() is GlossNode.Run) {
                        out.add(GlossNode.Space)
                    }
                    out.addAll(nodes)
                }
            }
        }
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

/** Tracks nested ordered/unordered list counters while walking structured JSON. */
private class ListContext {
    val counters = ArrayDeque<Int>()
}

private fun parseStructuredObject(obj: JSONObject): List<GlossNode>? =
    parseStructuredObject(obj, ListContext())

private fun parseStructuredObject(obj: JSONObject, ctx: ListContext): List<GlossNode>? {
    val style = obj.optJSONObject("style")?.let { parseStructuredStyle(it) }

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
        return runs.map { GlossNode.Run(it, bold = style?.bold ?: false, italic = style?.italic ?: false, underline = style?.underline ?: false, color = style?.color) }
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
            is JSONArray -> parseStructured(content.toString(), ctx) ?: emptyList()
            is JSONObject -> parseStructuredObject(content, ctx) ?: emptyList()
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
        val bold = obj.optBoolean("bold", false) || tagName in setOf("strong", "b") || (style?.bold == true)
        val italic = obj.optBoolean("italic", false) || tagName in setOf("em", "i") || (style?.italic == true)
        val underline = obj.optBoolean("underline", false) || tagName == "u" || (style?.underline == true)
        val styleColor = style?.color

        // Enter a list scope for ol/ul; descend into li counting children.
        if (tagName == "ol" || tagName == "ul") {
            ctx.counters.addLast(if (tagName == "ol") 0 else -1)
        }

        val out = mutableListOf<GlossNode>()
        for (child in children) {
            if (out.isNotEmpty() && child is GlossNode.Run && out.last() is GlossNode.Run) {
                out.add(GlossNode.Space)
            }
            val marker = if (tagName == "li" && ctx.counters.isNotEmpty()) {
                val idx = ctx.counters.size - 1
                val count = ctx.counters[idx]
                if (count >= 0) {
                    ctx.counters[idx] = count + 1
                    "${count + 1}. "
                } else {
                    "\u2022 "
                }
            } else null
            if (child is GlossNode.Run && (bold || italic || underline || styleColor != null || marker != null)) {
                out.add(
                    child.copy(
                        text = (marker ?: "") + child.text,
                        bold = child.bold || bold,
                        italic = child.italic || italic,
                        underline = child.underline || underline,
                        color = child.color ?: styleColor,
                    ),
                )
            } else {
                if (marker != null && child is GlossNode.Run) {
                    out.add(child.copy(text = marker + child.text))
                } else {
                    out.add(child)
                }
            }
        }
        if (tagName == "ol" || tagName == "ul") {
            if (ctx.counters.isNotEmpty()) ctx.counters.removeLast()
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
        val counters = ArrayDeque<Int>()
        for (child in doc.body()?.children() ?: emptyList()) {
            walkElement(child, out, counters)
        }
        // strip trailing breaks
        while (out.isNotEmpty() && out.last() == GlossNode.Break) out.removeAt(out.size - 1)
        out
    }.getOrNull()?.takeIf { it.isNotEmpty() }

private fun walkElement(el: Element, out: MutableList<GlossNode>, counters: ArrayDeque<Int>) {
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
    if (tag == "ol") counters.addLast(0)
    if (tag == "ul") counters.addLast(-1)

    val style = parseInlineStyle(el.attr("style"))
    val children = el.children()
    if (children.isEmpty()) {
        val text = el.ownText()
        if (text.isNotBlank()) {
            out.add(
                GlossNode.Run(
                    text = text,
                    bold = tag in setOf("b", "strong") || style.bold,
                    italic = tag in setOf("i", "em") || style.italic,
                    underline = tag == "u" || style.underline,
                    color = style.color,
                ),
            )
        }
        if (tag == "ol" || tag == "ul") counters.removeLastOrNull()
        return
    }

    if (tag in setOf("p", "div", "section", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "tr")) {
        out.add(GlossNode.Break)
    }
    val bold = tag in setOf("b", "strong") || style.bold
    val italic = tag in setOf("i", "em") || style.italic
    val underline = tag == "u" || style.underline
    var liMarker: String? = null
    if (tag == "li") {
        val parentTag = el.parent()?.tagName()
        liMarker = when (parentTag) {
            "ol" -> {
                val count = if (counters.isNotEmpty()) counters.last() else 0
                if (counters.isNotEmpty()) counters[counters.size - 1] = count + 1
                "${count + 1}. "
            }
            "ul" -> "\u2022 "
            else -> null
        }
    }
    for (child in children) {
        val start = out.size
        walkElement(child, out, counters)
        if (liMarker != null) {
            for (i in start until out.size) {
                val n = out[i]
                if (n is GlossNode.Run) {
                    out[i] = n.copy(text = liMarker + n.text, bold = n.bold || true)
                    liMarker = null
                    break
                }
            }
        }
        if (bold || italic || underline || style.color != null) {
            for (i in start until out.size) {
                val n = out[i]
                if (n is GlossNode.Run) {
                    out[i] = n.copy(
                        bold = n.bold || bold,
                        italic = n.italic || italic,
                        underline = n.underline || underline,
                        color = n.color ?: style.color,
                    )
                }
            }
        }
        if (out.isNotEmpty() && out.last() !is GlossNode.Break && iHasBlock(child)) {
            out.add(GlossNode.Break)
        }
    }
    if (liMarker != null) {
        out.add(GlossNode.Run(liMarker.trimEnd() + " ", bold = true))
    }
    if (tag == "ol" || tag == "ul") counters.removeLastOrNull()
}

/** Parsed subset of an element's `style` attribute. */
private class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Long? = null,
)

private fun parseInlineStyle(raw: String): InlineStyle {
    if (raw.isBlank()) return InlineStyle()
    var bold = false
    var italic = false
    var underline = false
    var color: Long? = null
    for (decl in raw.split(";")) {
        val parts = decl.split(":", limit = 2)
        if (parts.size != 2) continue
        val prop = parts[0].trim().lowercase()
        val value = parts[1].trim()
        when (prop) {
            "font-weight" -> bold = bold || value == "bold" || (value.toIntOrNull() ?: 0) >= 600
            "font-style" -> italic = italic || value == "italic"
            "text-decoration" -> underline = underline || value.contains("underline")
            "color", "background-color", "background" -> {
                if (color == null) color = parseCssColor(value)
            }
        }
    }
    return InlineStyle(bold, italic, underline, color)
}

/** Best-effort CSS color → ARGB Long. Handles hex (#rgb/#rrggbb/#aarrggbb). */
private fun parseCssColor(raw: String): Long? {
    val value = raw.trim().removePrefix("var(").removeSuffix(")").trim()
    val hex = value.removePrefix("#")
    if (value.startsWith("#") && hex.length in setOf(3, 4, 6, 8) && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "ff$hex"
            else -> hex
        }
        return full.toLong(16)
    }
    return null
}

/** Parse a structured-content `style` object into an [InlineStyle]. */
private fun parseStructuredStyle(style: JSONObject): InlineStyle {
    var bold = style.optBoolean("fontWeight", false) || style.optString("fontWeight", "").let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 }
    var italic = style.optBoolean("fontStyle", false) || style.optString("fontStyle", "") == "italic"
    var underline = style.optString("textDecoration", "").contains("underline") || style.optString("textDecorationLine", "").contains("underline")
    val color = parseCssColor(style.optString("color", "").takeIf { it.isNotBlank() } ?: "")
    return InlineStyle(bold, italic, underline, color)
}

private fun iHasBlock(el: Element): Boolean =
    el.tagName() in setOf("p", "div", "section", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table")
