package eu.kanade.tachiyomi.ui.dictionary.compose

/**
 * Parsed dictionary CSS (`styles.css`) extracted into a form the Compose renderer can
 * apply directly. Mirrors the reference implementation: selectorStyles is keyed by the
 * `data-sc-content` / `data-sc-class` values a node carries, so lookups are O(1).
 */
data class ParsedCss(
    val boxSelectors: Set<String> = emptySet(),
    val selectorStyles: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        val EMPTY = ParsedCss()
    }
}

/** Parses a dictionary's CSS text into [ParsedCss]. Never throws. */
fun parseDictionaryCss(cssText: String?): ParsedCss {
    if (cssText.isNullOrBlank()) return ParsedCss.EMPTY
    val cleaned = stripCssComments(cssText)
    if (cleaned.isBlank()) return ParsedCss.EMPTY

    val boxSelectors = mutableSetOf<String>()
    val selectorStyles = mutableMapOf<String, MutableMap<String, String>>()

    var i = 0
    while (i < cleaned.length) {
        val braceStart = cleaned.indexOf('{', i)
        if (braceStart == -1) break
        val selectorPart = cleaned.substring(i, braceStart).trim()
        // Find the matching close brace, accounting for nested `& ... {}` blocks.
        val braceEnd = findMatchingBraceEnd(cleaned, braceStart)
        if (braceEnd == -1) break
        val propertiesPart = cleaned.substring(braceStart + 1, braceEnd)
        val properties = parseProperties(propertiesPart)

        val dataSelectors = extractDataSelectors(selectorPart)
        if (dataSelectors.isEmpty() || properties.isEmpty()) {
            i = braceEnd + 1
            continue
        }

        val hasBoxProperty = properties.keys.any { key ->
            key.startsWith("background") ||
                key.startsWith("border") ||
                key.startsWith("padding") ||
                key.startsWith("margin") ||
                key == "clip-path"
        }

        for (selector in dataSelectors) {
            if (hasBoxProperty) boxSelectors.add(selector)
            val existing = selectorStyles.getOrPut(selector) { mutableMapOf() }
            properties.forEach { (key, value) ->
                existing[toCamelCase(key)] = value
            }
        }
        i = braceEnd + 1
    }

    return ParsedCss(boxSelectors, selectorStyles)
}

/** Merges all CSS rules that match an element's `data-*` attribute values. */
fun getCssStyles(dataAttributes: Map<String, String>, parsedCss: ParsedCss): Map<String, String> {
    return dataAttributes.values
        .mapNotNull { parsedCss.selectorStyles[it] }
        .fold(emptyMap()) { acc, map -> acc + map }
}

/**
 * True if a node (or one of its descendants) carries CSS-bearing semantics that the structured
 * renderer should handle: a matching box selector, a styled element, a block/box element, a
 * table, a details/summary, an image, or recognisable `data-*` content. Plain inline spans/text
 * return false so the fast path stays in effect.
 */
internal fun StructuredNode.isStructuredBox(parsedCss: ParsedCss?): Boolean = when (this) {
    is StructuredNode.Text, StructuredNode.TextBreak -> false
    is StructuredNode.Element -> {
        if (tag in structuredBoxTags) return true
        if (tag == StructuredTag.Image) return true
        if (attributes.style.isNotEmpty()) return true
        if (attributes.data.isNotEmpty()) {
            val pc = parsedCss
            if (pc == null || pc.boxSelectors.isEmpty()) {
                // No CSS available: treat elements with data-* or block semantics as structured.
                if (attributes.data["content"] == "attribution") return false
                if (tag in blockSignalTags) return true
            } else {
                val styles = getCssStyles(attributes.data, pc)
                if (styles.isNotEmpty()) return true
            }
        }
        children.any { it.isStructuredBox(parsedCss) }
    }
}

private val structuredBoxTags = setOf(
    StructuredTag.Table, StructuredTag.Thead, StructuredTag.Tbody, StructuredTag.Tfoot,
    StructuredTag.Tr, StructuredTag.Td, StructuredTag.Th,
    StructuredTag.Details, StructuredTag.Summary,
    StructuredTag.UnorderedList, StructuredTag.OrderedList, StructuredTag.ListItem,
    StructuredTag.Div,
)

private val blockSignalTags = setOf(
    StructuredTag.Div, StructuredTag.UnorderedList, StructuredTag.OrderedList, StructuredTag.ListItem,
    StructuredTag.Details, StructuredTag.Summary, StructuredTag.Table, StructuredTag.Tr,
)

/** Resolved box-ish styling from a combined CSS style map. */
data class BoxStyle(
    val hasBackground: Boolean = false,
    val hasBorder: Boolean = false,
    val leftAccent: Boolean = false,
    val borderRadius: Float? = null,
    val paddingStart: Float? = null,
    val paddingEnd: Float? = null,
    val paddingTop: Float? = null,
    val paddingBottom: Float? = null,
    val marginStart: Float? = null,
    val marginEnd: Float? = null,
    val marginTop: Float? = null,
    val marginBottom: Float? = null,
    val borderColor: String? = null,
    val backgroundColor: String? = null,
) {
    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null

    val hasMargin: Boolean
        get() = marginStart != null || marginEnd != null || marginTop != null || marginBottom != null

    val hasAnyStyle: Boolean
        get() = hasBackground || hasBorder || hasPadding || hasMargin
}

/**
 * Parses a combined style map (already camel-cased by [parseDictionaryCss]) into a [BoxStyle]
 * with dimensions in dp. `defaultFontSizePx` is the base font size used to convert em/rem.
 */
fun parseBoxStyle(styleMap: Map<String, String>, baseFontSizeSp: Float): BoxStyle {
    var hasBackground = false
    var hasBorder = false
    var leftAccent = false
    var borderRadius: Float? = null
    var paddingStart: Float? = null
    var paddingEnd: Float? = null
    var paddingTop: Float? = null
    var paddingBottom: Float? = null
    var marginStart: Float? = null
    var marginEnd: Float? = null
    var marginTop: Float? = null
    var marginBottom: Float? = null
    var borderColor: String? = null
    var backgroundColor: String? = null

    for ((key, value) in styleMap) {
        when (key) {
            "backgroundColor", "background" -> {
                backgroundColor = extractBackgroundColor(value)
                if (backgroundColor != null &&
                    backgroundColor != "transparent" &&
                    backgroundColor != "inherit" &&
                    backgroundColor != "none"
                ) {
                    hasBackground = true
                }
            }
            "border", "borderColor", "borderWidth", "borderLeft", "borderStyle", "borderLeftColor" -> {
                if (value.isNotBlank() && value != "none" && value != "0" && value != "0px") {
                    when (key) {
                        "borderStyle" -> {
                            // `none none none solid` / `none solid` → left accent bar only.
                            // A plain `solid` (all sides) is a full frame.
                            val tokens = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (tokens.isNotEmpty() &&
                                tokens.all { it == "none" || it == "solid" } &&
                                tokens.any { it == "solid" } &&
                                tokens.any { it == "none" }
                            ) {
                                leftAccent = true
                            }
                        }
                        "borderLeftColor" -> if (borderColor == null) {
                            borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
                            leftAccent = true
                        }
                        "borderColor" -> if (borderColor == null) {
                            borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
                            // `border-color` alone: CSS default style is none, but the reference
                            // renderer draws these as coloured accent boxes.
                            leftAccent = true
                        }
                    }
                    if (leftAccent) {
                        hasBorder = true
                    } else {
                        // `border-color` alone produces no visible box border in CSS (border-style
                        // defaults to none); treat it as an accent edge, not a full frame.
                        hasBorder = key == "border" || key == "borderWidth" || key == "borderLeft"
                        if (key == "borderWidth" && value != "0px" && value != "0") hasBorder = true
                        if (key == "borderStyle" && value.contains("solid")) hasBorder = true
                    }
                }
            }
            "borderRadius" -> borderRadius = parseDpValue(value, baseFontSizeSp)
            "padding" -> parseDpValue(value, baseFontSizeSp)?.let {
                paddingStart = it; paddingEnd = it; paddingTop = it; paddingBottom = it
            }
            "paddingLeft", "paddingInlineStart" -> paddingStart = parseDpValue(value, baseFontSizeSp)
            "paddingRight", "paddingInlineEnd" -> paddingEnd = parseDpValue(value, baseFontSizeSp)
            "paddingTop" -> paddingTop = parseDpValue(value, baseFontSizeSp)
            "paddingBottom" -> paddingBottom = parseDpValue(value, baseFontSizeSp)
            "margin" -> parseMarginValue(value, baseFontSizeSp)?.let {
                marginStart = it; marginEnd = it; marginTop = it; marginBottom = it
            }
            "marginLeft", "marginInlineStart" -> marginStart = parseMarginValue(value, baseFontSizeSp)
            "marginRight", "marginInlineEnd" -> marginEnd = parseMarginValue(value, baseFontSizeSp)
            "marginTop" -> marginTop = parseMarginValue(value, baseFontSizeSp)
            "marginBottom" -> marginBottom = parseMarginValue(value, baseFontSizeSp)
        }
    }

    // A `border-style: none none none solid` + left border-width means a left accent border.
    // Ensure a visible border even when `borderColor` came only as a shorthand `border` etc.
    return BoxStyle(
        hasBackground = hasBackground,
        hasBorder = hasBorder,
        leftAccent = leftAccent,
        borderRadius = borderRadius,
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingTop = paddingTop,
        paddingBottom = paddingBottom,
        marginStart = marginStart,
        marginEnd = marginEnd,
        marginTop = marginTop,
        marginBottom = marginBottom,
        borderColor = borderColor,
        backgroundColor = backgroundColor,
    )
}

private fun parseProperties(propertiesPart: String): Map<String, String> {
    val parsed = mutableMapOf<String, String>()
    val segment = StringBuilder()
    var i = 0
    while (i < propertiesPart.length) {
        val ch = propertiesPart[i]
        when {
            ch == '{' -> {
                // Nested `&::before { content: "X" }` blocks carry the marker glyphs used by
                // forms tables (form-pri/form-irr/...); surface their content on the parent rule.
                val end = findMatchingBraceEnd(propertiesPart, i)
                if (end == -1) break
                val nested = propertiesPart.substring(i + 1, end)
                if (nested.contains("content")) {
                    val before = parseProperties(nested)["content"]?.trim('"', '\'', ' ')
                    if (!before.isNullOrEmpty()) parsed["beforeContent"] = before
                }
                i = end
            }
            ch == ';' -> {
                addDeclaration(parsed, segment.toString())
                segment.clear()
            }
            else -> segment.append(ch)
        }
        i++
    }
    if (segment.isNotBlank()) addDeclaration(parsed, segment.toString())
    return parsed
}

private fun addDeclaration(out: MutableMap<String, String>, raw: String) {
    val colonIndex = raw.indexOf(':')
    if (colonIndex == -1) return
    val key = raw.take(colonIndex).trim().lowercase()
    val value = raw.substring(colonIndex + 1).trim()
    if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
}

/** Returns the index of the `}` matching the `{` at [openBraceIndex], or -1 if unbalanced. */
private fun findMatchingBraceEnd(css: String, openBraceIndex: Int): Int {
    var depth = 0
    var j = openBraceIndex
    while (j < css.length) {
        when (css[j]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return j
            }
        }
        j++
    }
    return -1
}

private fun extractDataSelectors(selectorPart: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < selectorPart.length) {
        val attrStart = selectorPart.indexOf("[data-sc-", i)
        if (attrStart == -1) break
        val equalsIndex = selectorPart.indexOf('=', attrStart)
        if (equalsIndex == -1) {
            i = attrStart + 1
            continue
        }
        val attrName = selectorPart.substring(attrStart + 1, equalsIndex)
        if (attrName != "data-sc-content" && attrName != "data-sc-class") {
            i = equalsIndex + 1
            continue
        }
        val valueStart = equalsIndex + 1
        if (valueStart >= selectorPart.length) break
        val quote = selectorPart[valueStart]
        if (quote != '\'' && quote != '"') {
            i = valueStart + 1
            continue
        }
        val valueEnd = selectorPart.indexOf(quote, valueStart + 1)
        if (valueEnd == -1) break
        val value = selectorPart.substring(valueStart + 1, valueEnd)
        if (value.isNotEmpty()) result.add(value)
        i = valueEnd + 1
    }
    return result
}

/** Extracts a usable color from `color-mix(in srgb, X 5%, transparent)`, `var(...)`, or plain colors. */
private fun extractBackgroundColor(value: String): String? {
    val trimmed = value.trim()
    val mixMatch = Regex("""color-mix\(in\s+srgb,\s*([^,]+),\s*transparent\)""").find(trimmed)
    if (mixMatch != null) {
        val inner = mixMatch.groupValues[1].trim()
        // `var(--text-color, var(--fg, #333))` → grab first hex seen
        Regex("""#[0-9a-fA-F]{3,8}""").findAll(inner).firstOrNull()?.value?.let { return it }
        val varMatch = Regex("""var\(--[^,)]+,\s*([^)]+)\)""").find(inner)
        if (varMatch != null) {
            val fallback = varMatch.groupValues[1].trim().removeSuffix(")")
            if (fallback.startsWith("#")) return fallback
        }
        // Named color such as `green`, `goldenrod`, `#1A73E8`
        Regex("""[a-zA-Z#][a-zA-Z0-9#]*""").find(inner)?.value?.let { candidate ->
            return candidate.removeSuffix(")").takeIf { it != "transparent" }
        }
        return null
    }
    val directVar = Regex("""var\(--[^,)]+,\s*([^)]+)\)""").find(trimmed)
    if (directVar != null) {
        val fallback = directVar.groupValues[1].trim().removeSuffix(")")
        if (fallback.startsWith("#")) return fallback
    }
    // `var(--text-color, var(--fg, #333))` (nested fallback) — grab first hex seen anywhere.
    if (trimmed.contains("var(")) {
        Regex("""#[0-9a-fA-F]{3,8}""").find(trimmed)?.value?.let { return it }
    }
    return trimmed.takeIf { it.startsWith("#") }
}

/** Strips block comments. */
private fun stripCssComments(css: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < css.length) {
        if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
            val end = css.indexOf("*/", i + 2)
            if (end == -1) break
            i = end + 2
        } else {
            result.append(css[i])
            i++
        }
    }
    return result.toString()
}

/** Converts a CSS property name to camelCase. */
internal fun toCamelCase(cssProperty: String): String {
    val parts = cssProperty.split('-')
    if (parts.size == 1) return parts[0]
    return buildString {
        append(parts[0])
        for (j in 1 until parts.size) {
            val part = parts[j]
            if (part.isNotEmpty()) {
                append(part[0].uppercaseChar())
                append(part.substring(1))
            }
        }
    }
}

/** Parses a CSS dimension (px/em/rem/dp/unitless) into dp, using [baseFontSizeSp] for em/rem. */
internal fun parseDpValue(value: String, baseFontSizeSp: Float): Float? {
    val trimmed = value.trim().lowercase()
    return when {
        trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()
        trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("em") -> trimmed.removeSuffix("em").toFloatOrNull()?.times(baseFontSizeSp)
        trimmed.endsWith("dp") -> trimmed.removeSuffix("dp").toFloatOrNull()
        else -> trimmed.toFloatOrNull()
    }
}

/**
 * For `margin*` in the JSON style object, bare numbers are em units (reference renderer appends
 * `em` to numeric margin values). CSS text always carries an explicit unit, so only the numeric
 * JSON path is affected here.
 */
private fun parseMarginValue(value: String, baseFontSizeSp: Float): Float? {
    val trimmed = value.trim().lowercase()
    return if (trimmed.toFloatOrNull() != null) {
        trimmed.toFloatOrNull()?.times(baseFontSizeSp)
    } else {
        parseDpValue(trimmed, baseFontSizeSp)
    }
}