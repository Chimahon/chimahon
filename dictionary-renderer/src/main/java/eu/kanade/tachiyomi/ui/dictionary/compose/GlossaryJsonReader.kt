package eu.kanade.tachiyomi.ui.dictionary.compose

import org.json.JSONObject
import org.json.JSONTokener

/** Streams content, metadata and styles directly into renderer nodes and their attribute maps. */
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

    private fun content(): List<StructuredNode> {
        val first = input.nextClean()
        input.back()
        if (first == '[' || first == '{') return ArrayList<StructuredNode>().also(::value)
        return when (val scalar = input.nextValue()) {
            is String -> if (scalar.isEmpty()) emptyList() else listOf(StructuredNode.Text(scalar))
            is Number, is Boolean -> listOf(StructuredNode.Text(scalar.toString()))
            else -> emptyList()
        }
    }

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
        if (text != null) return StructuredNode.Element(StructuredTag.Span, text!!,
            StructuredAttributes.create(emptyMap(), style, emptyMap()))
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
        return StructuredNode.Element(resolvedTag, children, StructuredAttributes.create(data, style, filtered))
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

    private fun scalarValue(): Any? = when (input.nextClean()) {
        '{', '[' -> { input.back(); skipValue(); null }
        else -> { input.back(); input.nextValue().takeUnless { it == JSONObject.NULL } }
    }

    private fun metadata(): Map<String, String> {
        if (input.nextClean() != '{') {
            input.back()
            skipValue()
            return emptyMap()
        }
        // Empty values are meaningful: the structured payload marks attributes by presence
        // (`"headword": ""` -> `data-sc-headword=""`), and the dictionary CSS selects on them.
        val out = LinkedHashMap<String, String>()
        members { key ->
            val name = structuredDataName(key)
            val value = scalarValue()
            if (name != null) {
                if (value == null) out.remove(name) else out[name] = value.toString()
            }
        }
        return if (out.isEmpty()) emptyMap() else out
    }

    /**
     * A payload node's `style` object only reaches the WebView renderer through a fixed property
     * whitelist (renderer.js `applyStyle`); every other key is dropped there. `fontSize` is the one
     * that matters most: Sankoku's reading spans set 0.65em, which the reference ignores, so honouring
     * it here drew those readings a third narrower than the reference and re-wrapped the line.
     * Numbers are only meaningful for the four margin sides, which renderer.js turns into `em`.
     */
    private fun inlineStyle(): Map<String, String> {
        if (input.nextClean() != '{') {
            input.back()
            skipValue()
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        var decorationLine = ""
        members { key ->
            val name = toCamelCase(key)
            when (name) {
                "textDecorationLine" -> decorationLine = readDecorationLine()
                in PAYLOAD_STYLE_MARGINS -> {
                    val value = scalarValue()
                    when {
                        value is Number -> out[name] = "${value}em"
                        value is String && value.isNotEmpty() -> out[name] = value
                        else -> out.remove(name)
                    }
                }
                in PAYLOAD_STYLE_PROPERTIES -> {
                    val value = scalarValue()
                    if (value is String && value.isNotEmpty()) out[name] = value else out.remove(name)
                }
                else -> skipValue()
            }
        }
        if (out["textDecoration"].isNullOrEmpty() && decorationLine.isNotEmpty()) out["textDecoration"] = decorationLine
        return if (out.isEmpty()) emptyMap() else out
    }

    private fun readDecorationLine(): String {
        if (input.nextClean() != '[') {
            input.back()
            return scalarValue() as? String ?: ""
        }
        if (input.nextClean() == ']') return ""
        input.back()
        return buildString {
            var first = true
            while (true) {
                if (!first) append(' ')
                append(input.nextValue().toString())
                first = false
                when (input.nextClean()) {
                    ']' -> return@buildString
                    ',' -> Unit
                    else -> error("Expected decoration array separator")
                }
            }
        }
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
