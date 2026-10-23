package eu.kanade.tachiyomi.ui.dictionary.compose

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Reads content directly into renderer nodes. Only small data/style objects use JSON maps. */
public class GlossaryJsonReader(raw: String) {
    private val input = JSONTokener(raw)

    fun read(): List<StructuredNode> = ArrayList<StructuredNode>().also {
        value(it)
        require(input.nextClean() == '\u0000') { "Trailing glossary JSON" }
    }

    private fun value(out: MutableList<StructuredNode>) {
        when (input.nextClean()) {
            '[' -> {
                if (input.nextClean() == ']') return
                input.back()
                while (true) {
                    value(out)
                    when (input.nextClean()) {
                        ']' -> return
                        ',' -> Unit
                        else -> error("Expected glossary array separator")
                    }
                }
            }
            '{' -> element()?.let(out::add)
            else -> {
                input.back()
                when (val scalar = input.nextValue()) {
                    is String -> if (scalar.isNotEmpty()) out.add(StructuredNode.Text(scalar))
                    is Number, is Boolean -> out.add(StructuredNode.Text(scalar.toString()))
                }
            }
        }
    }

    private fun content(): List<StructuredNode> = ArrayList<StructuredNode>().also(::value)

    private inline fun members(consume: (String) -> Unit): Int {
        if (input.nextClean() == '}') return 0
        input.back()
        var count = 0
        while (true) {
            val key = input.nextValue() as? String ?: error("Expected glossary member name")
            require(input.nextClean() == ':') { "Expected glossary member value" }
            consume(key)
            count++
            when (input.nextClean()) {
                '}' -> return count
                ',' -> Unit
                else -> error("Expected glossary object separator")
            }
        }
    }

    private fun element(): StructuredNode? {
        var type = ""
        var tag = ""
        var children: List<StructuredNode> = emptyList()
        var text: List<StructuredNode>? = null
        var ruby: Pair<List<StructuredNode>, List<StructuredNode>>? = null
        var data: Map<String, String> = emptyMap()
        var style: Map<String, String> = emptyMap()
        var properties: MutableMap<String, String>? = null
        fun property(key: String, value: String?) {
            if (value != null) (properties ?: LinkedHashMap<String, String>().also { properties = it })[key] = value
        }
        val count = members { key ->
            when (key) {
                "type" -> type = scalarString()
                "tag" -> tag = scalarString()
                "content" -> children = content()
                "text" -> text = content()
                "ruby" -> ruby = ruby()
                "data" -> data = metadata()
                "style" -> style = inlineStyle()
                "href", "title", "lang", "path", "src", "sizeUnits", "verticalAlign", "appearance" ->
                    property(key, scalarString().takeIf { it.isNotEmpty() })
                "colSpan", "rowSpan", "width", "height", "preferredWidth", "preferredHeight" ->
                    property(key, (input.nextValue() as? Number)?.toString())
                "open", "background", "collapsed" -> {
                    val v = input.nextValue()
                    property(key, "true".takeIf { v == true || v is String && v.equals("true", ignoreCase = true) })
                }
                else -> skipValue()
            }
        }
        if (count == 0) return null
        if (type == "structured-content") return when (children.size) {
            0 -> null
            1 -> children[0]
            else -> StructuredNode.Element(StructuredTag.Span, children)
        }
        val props = properties ?: emptyMap()
        if (type == "image") return StructuredNode.Element(StructuredTag.Image, attributes = StructuredAttributes(
            data = data, style = style, properties = buildMap {
                (props["path"] ?: props["src"])?.let { put("path", it) }
                props["title"]?.let { put("title", it) }
            }))
        if (text != null) return StructuredNode.Element(StructuredTag.Span, text!!, StructuredAttributes(style = style))
        if (ruby != null) {
            val base = buildString { ruby!!.first.forEach { it.appendCollectedTo(this) } }
            val reading = buildString { ruby!!.second.forEach { it.appendCollectedTo(this) } }
            return StructuredNode.Element(StructuredTag.Ruby, buildList {
                if (base.isNotBlank()) add(StructuredNode.Text(base))
                if (reading.isNotBlank()) add(StructuredNode.Element(StructuredTag.Rt, listOf(StructuredNode.Text(reading))))
            })
        }
        val rawTag = tag.trim().lowercase()
        val resolvedTag = if (rawTag == "img") StructuredTag.Image else StructuredTag.fromRaw(rawTag)
        val filtered = if (resolvedTag == StructuredTag.Image) buildMap {
            putAll(props)
            remove("src")
            (props["path"] ?: props["src"])?.let { put("path", it) }
        } else if (props.keys.all { it in TEXT_PROPERTIES }) props else props.filterKeys { it in TEXT_PROPERTIES }
        return StructuredNode.Element(resolvedTag, children, StructuredAttributes(data, style, filtered))
    }

    private fun ruby(): Pair<List<StructuredNode>, List<StructuredNode>> {
        if (input.nextClean() != '{') {
            input.back()
            skipValue()
            return emptyList<StructuredNode>() to emptyList()
        }
        var base: List<StructuredNode> = emptyList()
        var reading: List<StructuredNode> = emptyList()
        members { key -> when (key) {
            "text" -> base = content()
            "rt" -> reading = content()
            else -> skipValue()
        } }
        return base to reading
    }

    private fun scalarString(): String = input.nextValue().let { if (it == JSONObject.NULL) "" else it.toString() }

    private fun metadata(): Map<String, String> {
        val obj = input.nextValue() as? JSONObject ?: return emptyMap()
        if (obj.length() == 0) return emptyMap()
        // Empty values are meaningful: the structured payload marks attributes by presence
        // (`"headword": ""` -> `data-sc-headword=""`), and the dictionary CSS selects on them.
        return buildMap {
            obj.keys().forEach { key ->
                val v = obj.opt(key)
                if (v != null && v != JSONObject.NULL && v !is JSONObject && v !is JSONArray) {
                    put(toCamelCase(key), v.toString())
                }
            }
        }
    }

    /**
     * A payload node's `style` object only reaches the WebView renderer through a fixed property
     * whitelist (renderer.js `applyStyle`); every other key is dropped there. `fontSize` is the one
     * that matters most: Sankoku's reading spans set 0.65em, which the reference ignores, so honouring
     * it here drew those readings a third narrower than the reference and re-wrapped the line.
     * Numbers are only meaningful for the four margin sides, which renderer.js turns into `em`.
     */
    private fun inlineStyle(): Map<String, String> {
        val obj = input.nextValue() as? JSONObject ?: return emptyMap()
        if (obj.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var decorationLine = ""
        for (key in obj.keys()) {
            val name = toCamelCase(key)
            val value = obj.opt(key) ?: continue
            if (value == JSONObject.NULL) continue
            when {
                name == "textDecorationLine" -> decorationLine = when {
                    value is JSONArray -> (0 until value.length()).joinToString(" ") { value.optString(it) }
                    value is String -> value
                    else -> ""
                }
                name in PAYLOAD_STYLE_MARGINS -> when {
                    value is Number -> out[name] = "${value.toString()}em"
                    value is String && value.isNotEmpty() -> out[name] = value
                }
                name in PAYLOAD_STYLE_PROPERTIES && value is String && value.isNotEmpty() -> out[name] = value
            }
        }
        if (out["textDecoration"].isNullOrEmpty() && decorationLine.isNotEmpty()) out["textDecoration"] = decorationLine
        return out
    }

    private fun skipValue() {
        when (input.nextClean()) {
            '[' -> {
                if (input.nextClean() == ']') return
                input.back()
                while (true) {
                    skipValue()
                    when (input.nextClean()) {
                        ']' -> return
                        ',' -> Unit
                        else -> error("Expected JSON array separator")
                    }
                }
            }
            '{' -> members { skipValue() }
            else -> { input.back(); input.nextValue() }
        }
    }

    private companion object {
        val TEXT_PROPERTIES = setOf("href", "title", "lang", "colSpan", "rowSpan", "open")

        /** Exactly renderer.js `applyStyle`'s property map, which drops every other payload style. */
        val PAYLOAD_STYLE_PROPERTIES = setOf(
            "fontStyle", "fontWeight", "color", "background", "backgroundColor", "textDecorationStyle",
            "textDecorationColor", "textDecoration", "borderColor", "borderStyle", "borderRadius",
            "borderWidth", "clipPath", "verticalAlign", "textAlign", "textEmphasis", "textShadow",
            "margin", "padding", "paddingTop", "paddingLeft", "paddingRight", "paddingBottom",
            "whiteSpace", "wordBreak", "cursor", "listStyleType",
        )

        /** The four sides renderer.js turns a bare number into `em`. */
        val PAYLOAD_STYLE_MARGINS = setOf("marginTop", "marginLeft", "marginRight", "marginBottom")
    }
}
