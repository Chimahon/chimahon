package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.Immutable

/**
 * A CSS rule that matches by attribute *presence* rather than a literal value: `[data-sc-meaning]`
 * or a compound like `[data-sc-logo][data-sc-round]` (all keys required, e.g. Shinmeikai Kokugo).
 */
@Immutable
data class CssPresenceRule(
    val requiredKeys: Set<String>,
    val styles: Map<String, String>,
    val isBox: Boolean,
)

/**
 * A tag-scoped rule whose applicability depends on the ancestor chain, e.g.
 * `div[data-sc-content="forms"] { & th { font-weight: normal } }` or the
 * equivalent descendant compound `tr[data-sc-content="forms-header-row"] th`.
 * Stored separately from flat [ParsedCss.selectorStyles] so ancestor context
 * (not just the element's own attributes) decides the match.
 */
@Immutable
data class NestedTagRule(
    /** Data values / `.class` tokens required in the ancestor chain. */
    val ancestors: Set<String> = emptySet(),
    /** Required tag somewhere in the ancestor chain (null = any ancestor). */
    val parentTag: String? = null,
    /** Only match a directly-parented element (child `>` combinator). */
    val direct: Boolean = false,
    /** Element tag to match, lowercase; empty = any tag carrying [qualifier]. */
    val tag: String = "",
    /** Required `data-*` entries on the element itself (`class` = token match). */
    val qualifier: Map<String, String> = emptyMap(),
    val styles: Map<String, String> = emptyMap(),
    val isBox: Boolean = false,
)

/** One ancestor frame used for [NestedTagRule] matching. */
@Immutable
data class CssAncestor(
    /** Lookup keys of the ancestor's data (`content` values, class tokens …). */
    val values: Set<String> = emptySet(),
    /** Ancestor element tag, lowercase (`div`, `td`, …). */
    val tag: String = "",
)

/**
 * Parsed dictionary CSS (`styles.css`) extracted into a form the Compose renderer can
 * apply directly. Mirrors the reference implementation: selectorStyles is keyed by the
 * `data-sc-content` / `data-sc-class` values a node carries, so lookups are O(1); presenceRules
 * handle attribute-presence / compound selectors like `[data-sc-meaning]`; nestedTagRules
 * handle tag-scoped rules (`& th`, `tr[x] th`) via the ancestor chain.
 */
@Immutable
data class SiblingRule(
    /** Attribute NAME when the right compound is presence-only (`[data-sc-meaning]`), else null. */
    val thisPresence: String?,
    /** Lookup key when the right compound carries a value or class, else null. */
    val thisKey: String?,
    /** Attribute NAME when the left compound is presence-only (`[data-sc-title2]`), else null. */
    val prevPresence: String?,
    /** Lookup key when the left compound carries a value or class, else null. */
    val prevKey: String?,
    /** True for `+` (immediately preceding), false for `~` (any earlier sibling). */
    val adjacent: Boolean,
    val styles: Map<String, String>,
) {
    fun matchesSelf(data: Map<String, String>): Boolean =
        if (thisPresence != null) thisPresence in data.keys else thisKey != null && thisKey in lookupKeysOf(data)

    fun matchesPrev(data: Map<String, String>): Boolean =
        if (prevPresence != null) prevPresence in data.keys else prevKey != null && prevKey in lookupKeysOf(data)
}

@Immutable
data class ParsedCss(
    val boxSelectors: Set<String> = emptySet(),
    val selectorStyles: Map<String, Map<String, String>> = emptyMap(),
    /** Styles from `A + B` / `A ~ B` rules, resolved by [getSiblingStyles]. */
    val siblingRules: List<SiblingRule> = emptyList(),
    val presenceRules: List<CssPresenceRule> = emptyList(),
    val nestedTagRules: List<NestedTagRule> = emptyList(),
) {
    /** True if any rule (value-, presence- or tag-based) carries box semantics. */
    val hasBoxRules: Boolean
        get() = boxSelectors.isNotEmpty() || presenceRules.any { it.isBox } ||
            nestedTagRules.any { it.isBox }

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
    val siblingRules = mutableListOf<SiblingRule>()
    val presenceRules = mutableListOf<CssPresenceRule>()
    val nestedTagRules = mutableListOf<NestedTagRule>()

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

        val presence = extractPresenceSelectors(selectorPart)
        // Sibling combinators `A + B` (adjacent) and `A ~ B` (general). Both are kept as rules keyed
        // on B, carrying the requirement that a preceding sibling match A, and are resolved by
        // [getSiblingStyles] once the caller knows what actually precedes the node in the flow.
        // Restricting this to `A + A` - as an earlier revision did - silently dropped every
        // cross-compound rule in the dictionary stylesheets.
        splitSelectorList(selectorPart).forEach { part ->
            val sib = splitSiblingCombinator(part) ?: return@forEach
            val thisSide = compoundKeyOf(sib.right)
            val prevSide = compoundKeyOf(sib.left)
            if (!thisSide.matches(emptyMap()) && thisSide.presences.isEmpty() && thisSide.lookupKeys.isEmpty()) {
                return@forEach
            }
            if (prevSide.presences.isEmpty() && prevSide.lookupKeys.isEmpty()) return@forEach
            siblingRules.add(
                SiblingRule(
                    thisPresence = thisSide.presences.singleOrNull(),
                    thisKey = thisSide.lookupKeys.singleOrNull(),
                    prevPresence = prevSide.presences.singleOrNull(),
                    prevKey = prevSide.lookupKeys.singleOrNull(),
                    adjacent = sib.adjacent,
                    styles = properties.mapKeys { toCamelCase(it.key) },
                ),
            )
        }
        // Compound selectors (`[a][b="v"]`, `.x.y`) target a conjunction we can't flatten:
        // indexing either value alone would style every node matching just one side. Counted per
        // selector so one compound in a list doesn't disable the plain selectors beside it.
        val allValueSelectors = splitSelectorList(selectorPart).flatMap { part ->
            if (part.count { it == '[' } <= 1) {
                extractDataSelectors(part) + extractClassSelectors(part)
            } else {
                extractClassSelectors(part)
            }
        }
        if ((allValueSelectors.isEmpty() && presence.isEmpty()) || properties.isEmpty()) {
            // Even when the flat rule is unusable, descendant compounds
            // (`tr[x] th`) and nested `& tag` blocks may still yield tag rules.
            parseNestedBlocks(propertiesPart, allValueSelectors.toSet(), null, nestedTagRules)
            for (part in splitSelectorList(selectorPart)) {
                parseDescendantCompound(part, properties)?.let { nestedTagRules.add(it) }
            }
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

        for (selector in allValueSelectors) {
            if (hasBoxProperty) boxSelectors.add(selector)
            val existing = selectorStyles.getOrPut(selector) { mutableMapOf() }
            properties.forEach { (key, value) ->
                existing[toCamelCase(key)] = value
            }
        }
        for (requiredKeys in presence) {
            presenceRules.add(
                CssPresenceRule(
                    requiredKeys = requiredKeys,
                    styles = properties.mapKeys { toCamelCase(it.key) },
                    isBox = hasBoxProperty,
                ),
            )
        }
        // Nested `& tag { ... }` blocks (CSS nesting): tag-scoped rules whose
        // ancestors are this rule's own selector keys.
        parseNestedBlocks(propertiesPart, allValueSelectors.toSet(), null, nestedTagRules)
        // Descendant compounds (`A sel tag`, `A > tag`): same shape as nesting.
        for (part in splitSelectorList(selectorPart)) {
            parseDescendantCompound(part, properties)?.let { nestedTagRules.add(it) }
        }
        i = braceEnd + 1
    }

    return ParsedCss(boxSelectors, selectorStyles, siblingRules, presenceRules, nestedTagRules)
}

/**
 * Merges all CSS rules that match an element's `data-*` attribute values.
 */
fun getCssStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
): Map<String, String> {
    if (dataAttributes.isEmpty() &&
        parsedCss.presenceRules.isEmpty()
    ) {
        return emptyMap()
    }
    val lookupKeys = lookupKeysOf(dataAttributes)
    if (lookupKeys.isEmpty() && parsedCss.presenceRules.isEmpty()) return emptyMap()
    // Mutable accumulation in source order (later rules win): the old
    // `acc + map` fold copied the whole map per matching rule.
    var merged: MutableMap<String, String>? = null
    for (key in lookupKeys) {
        val styles = parsedCss.selectorStyles[key] ?: continue
        if (merged == null) merged = HashMap(styles)
        else merged.putAll(styles)
    }
    for (rule in parsedCss.presenceRules) {
        if (dataAttributes.keys.containsAll(rule.requiredKeys)) {
            if (merged == null) merged = HashMap(rule.styles)
            else merged.putAll(rule.styles)
        }
    }
    return merged ?: emptyMap()
}

/**
 * Lookup keys for an element's data map: `content` values plus class tokens
 * (as both `.name` and bare `name`, mirroring [getCssStyles]). Used to match
 * [NestedTagRule.ancestors] and to build [CssAncestor] frames.
 */
internal fun dataValueKeys(dataAttributes: Map<String, String>): Set<String> {
    val out = mutableSetOf<String>()
    for ((k, v) in dataAttributes) {
        if (k == "class") {
            v.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.forEach { token ->
                out.add(".$token")
                out.add(token)
            }
        } else if (v.isNotBlank()) {
            out.add(v)
        }
    }
    return out
}

/**
 * Merges [NestedTagRule] styles matching an element ([tag], lowercase) with the
 * given ancestor chain. Rules apply in source order (later wins), exactly like
 * the flat cascade in [getCssStyles].
 */
internal fun getNestedStyles(
    tag: String,
    dataAttributes: Map<String, String>,
    ancestors: List<CssAncestor>,
    parsedCss: ParsedCss,
): Map<String, String> {
    if (parsedCss.nestedTagRules.isEmpty() || tag.isEmpty()) return emptyMap()
    val values = ancestors.flatMapTo(mutableSetOf()) { it.values }
    if (values.isEmpty()) return emptyMap()
    val tags = ancestors.mapTo(mutableSetOf()) { it.tag }
    val parent = ancestors.lastOrNull()
    var merged = emptyMap<String, String>()
    for (rule in parsedCss.nestedTagRules) {
        if (rule.tag.isNotEmpty() && rule.tag != tag) continue
        if (rule.qualifier.isNotEmpty() && !qualifierMatches(rule.qualifier, dataAttributes)) continue
        if (rule.direct) {
            // Child combinator: the DIRECT parent carries the ancestor values.
            if (parent == null || !parent.values.containsAll(rule.ancestors)) continue
            if (rule.parentTag != null && parent.tag != rule.parentTag) continue
        } else {
            if (!values.containsAll(rule.ancestors)) continue
            if (rule.parentTag != null && !tags.contains(rule.parentTag)) continue
        }
        merged = merged + rule.styles
    }
    return merged
}

private fun qualifierMatches(qualifier: Map<String, String>, data: Map<String, String>): Boolean =
    qualifier.all { (k, v) ->
        if (k == "class") {
            data[k]?.split(WHITESPACE_REGEX)?.any { it == v } == true
        } else {
            data[k] == v
        }
    }

/**
 * Scans a rule body for nested `& <selector> { ... }` blocks (CSS nesting) and
 * records them as [NestedTagRule]s scoped to [ancestors]. Recurses so
 * `& td { ... & span { ... } }` yields a span rule parented under `td`.
 * `&::before`-style pseudo rules are skipped (owned by the beforeContent path).
 */
private fun parseNestedBlocks(
    body: String,
    ancestors: Set<String>,
    parentTag: String?,
    out: MutableList<NestedTagRule>,
) {
    if (ancestors.isEmpty()) return
    var i = 0
    while (i < body.length) {
        val amp = body.indexOf('&', i)
        if (amp == -1) break
        // `&` must open a nested selector, not sit inside a value (e.g. content:"&").
        val prev = if (amp > 0) body[amp - 1] else ';'
        if (prev != ';' && prev != '{' && prev != '}' && !prev.isWhitespace()) {
            i = amp + 1
            continue
        }
        val brace = body.indexOf('{', amp)
        if (brace == -1) break
        val sel = body.substring(amp + 1, brace).trim()
        val end = findMatchingBraceEnd(body, brace)
        if (end == -1) break
        val inner = body.substring(brace + 1, end)
        if (!sel.startsWith(":") && !sel.startsWith("[")) {
            parseNestedTagSelector(sel)?.let { (tag, qualifier, direct) ->
                val props = parseProperties(inner)
                if (props.isNotEmpty()) {
                    out.add(
                        NestedTagRule(
                            ancestors = ancestors,
                            parentTag = parentTag,
                            direct = direct,
                            tag = tag,
                            qualifier = qualifier,
                            styles = props.mapKeys { toCamelCase(it.key) },
                            isBox = props.keys.any {
                                it.startsWith("background") || it.startsWith("border") ||
                                    it.startsWith("padding") || it.startsWith("margin") ||
                                    it == "clip-path"
                            },
                        ),
                    )
                }
                // Recurse: deeper `& x` blocks are parented under this tag (when known).
                parseNestedBlocks(inner, ancestors, tag.takeIf { it.isNotEmpty() } ?: parentTag, out)
            }
        }
        i = end + 1
    }
}

private val NESTED_TAG_SELECTOR_REGEX =
    Regex("""^(?:>\s*)?([a-zA-Z][a-zA-Z0-9]*)?\s*(\[[^\]]+\]|\.[a-zA-Z][a-zA-Z0-9_-]*)?$""")

/**
 * Parses a nested tag selector (`th`, `span[data-sc-content="x"]`, `> span`)
 * into (tag, qualifier, direct). Returns null for anything more complex
 * (combinators, pseudo-classes, multi-qualifiers).
 */
private fun parseNestedTagSelector(sel: String): Triple<String, Map<String, String>, Boolean>? {
    val trimmed = sel.trim()
    if (trimmed.isEmpty()) return null
    val direct = trimmed.startsWith(">")
    val m = NESTED_TAG_SELECTOR_REGEX.matchEntire(trimmed) ?: return null
    val tag = m.groupValues[1].lowercase()
    val qualRaw = m.groupValues[2]
    if (tag.isEmpty() && qualRaw.isEmpty()) return null
    val qualifier = mutableMapOf<String, String>()
    if (qualRaw.startsWith("[")) {
        val eq = qualRaw.indexOf('=')
        if (eq == -1) return null
        val name = qualRaw.substring(1, eq).trim().removePrefix("data-sc-")
        val value = qualRaw.substring(eq + 1).trim().trimEnd(']').trim('\'', '"', ' ', '\t')
        if (name.isEmpty() || value.isEmpty()) return null
        qualifier[name] = value
    } else if (qualRaw.startsWith(".")) {
        qualifier["class"] = qualRaw.removePrefix(".")
    }
    return Triple(tag, qualifier, direct)
}

/**
 * Parses a top-level descendant/child compound (`A sel tag`, `A > tag`) into a
 * [NestedTagRule] carrying [properties]. Sibling combinators (`+`, `~`),
 * pseudo-classes and complex targets are skipped — those address a different
 * element or position.
 */
private fun parseDescendantCompound(part: String, properties: Map<String, String>): NestedTagRule? {
    val trimmed = part.trim()
    if (trimmed.isEmpty() || !trimmed.contains("[data-sc-")) return null
    // Split at the last top-level (bracket-depth-0) combinator: space, `>` (`+`/`~` unsupported).
    var depth = 0
    var splitAt = -1
    var op = ' '
    var k = trimmed.length - 1
    while (k >= 0) {
        when (trimmed[k]) {
            ']' -> depth++
            '[' -> depth--
            '>', '+', '~' -> if (depth == 0) {
                splitAt = k
                op = trimmed[k]
                break
            }
            ' ', '\t' -> if (depth == 0) {
                // Only a combinator if the right side parses as a tag selector.
                val right = trimmed.substring(k + 1).trim()
                if (right.isNotEmpty() && NESTED_TAG_SELECTOR_REGEX.matches(right) &&
                    !right.startsWith(">") && !right.startsWith("[") && !right.startsWith(":")
                ) {
                    splitAt = k
                    op = ' '
                    break
                }
            }
        }
        k--
    }
    if (splitAt == -1 || op == '+' || op == '~') return null
    val leftRaw = trimmed.substring(0, splitAt).trim()
    // `A > B` (spaces around `>`): the `>` survives at the end of the left side.
    val direct = op == '>' || leftRaw.endsWith('>')
    val left = leftRaw.trimEnd('>', ' ', '\t')
    val right = trimmed.substring(splitAt + 1).trim().trimStart('>', ' ', '\t')
    if (left.isEmpty() || right.isEmpty()) return null
    if (right.any { it == '>' || it == '+' || it == '~' || it == ':' || it == ' ' || it == '\t' }) return null
    // The ancestor side must be a simple single-scope selector (no pseudo/combinators).
    if (left.any { it == '>' || it == '+' || it == '~' || it == ':' }) return null
    if (left.any { it == ' ' || it == '\t' }) return null
    val ancestorKeys = (extractDataSelectors(left) + extractClassSelectors(left)).toSet()
    if (ancestorKeys.isEmpty()) return null
    val (tag, qualifier, _) = parseNestedTagSelector(right) ?: return null
    if (tag.isEmpty()) return null
    val camel = properties.mapKeys { toCamelCase(it.key) }
    return NestedTagRule(
        ancestors = ancestorKeys,
        parentTag = null,
        direct = direct,
        tag = tag,
        qualifier = qualifier,
        styles = camel,
        isBox = camel.keys.any {
            it.startsWith("background") || it.startsWith("border") ||
                it.startsWith("padding") || it.startsWith("margin") ||
                it == "clip-path"
        },
    )
}

/** Resolved box-ish styling from a combined CSS style map. */
@Immutable
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
    val opacity: Float? = null,
    val textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    val verticalAlign: String? = null,
    val whiteSpace: String? = null,
    val wordBreak: String? = null,
    /** `width: fit-content` — shrink-wrap instead of stretching to the parent. */
    val fitContent: Boolean = false,
) {
    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null

    val hasMargin: Boolean
        get() = marginStart != null || marginEnd != null || marginTop != null || marginBottom != null

    val hasAnyStyle: Boolean
        get() = hasBackground || hasBorder || hasPadding || hasMargin ||
            opacity != null || textAlign != null || verticalAlign != null
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
    var opacity: Float? = null
    var textAlign: androidx.compose.ui.text.style.TextAlign? = null
    var verticalAlign: String? = null
    var whiteSpace: String? = null
    var wordBreak: String? = null
    var fitContent: Boolean = false

    for ((key, value) in styleMap) {
        when (key) {
            "width" -> {
                if (value.trim().lowercase() == "fit-content") fitContent = true
            }
            "opacity" -> {
                value.trim().toFloatOrNull()?.let { parsed ->
                    if (parsed in 0f..1f) opacity = parsed
                    else if (parsed > 1f && parsed <= 100f) opacity = (parsed / 100f).coerceIn(0f, 1f)
                }
            }
            "textAlign" -> {
                textAlign = when (value.trim().lowercase()) {
                    "right", "end" -> androidx.compose.ui.text.style.TextAlign.End
                    "center" -> androidx.compose.ui.text.style.TextAlign.Center
                    "left", "start" -> androidx.compose.ui.text.style.TextAlign.Start
                    "justify" -> androidx.compose.ui.text.style.TextAlign.Justify
                    else -> null
                }
            }
            "verticalAlign" -> {
                // Only "super" is high-impact (109k uses for 0.7em super notes)
                if (value.trim().lowercase() == "super" || value.trim().lowercase() == "sub") {
                    verticalAlign = value.trim().lowercase()
                }
            }
            "whiteSpace" -> {
                // e.g. "nowrap" on tag chips — no Compose wrap prevention needed beyond FlowRow,
                // but parse safely so it doesn't leak.
                whiteSpace = value.trim().lowercase()
            }
            "wordBreak" -> {
                // "keep-all" has no Compose equivalent; ensure we don't break CJK incorrectly.
                wordBreak = value.trim().lowercase()
            }
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
                            val tokens = value.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
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
            "padding" -> {
                val (top, right, bottom, left) = parseFourValue(value, baseFontSizeSp)
                if (top != null) paddingTop = top
                if (right != null) paddingEnd = right
                if (bottom != null) paddingBottom = bottom
                if (left != null) paddingStart = left
            }
            "paddingLeft", "paddingInlineStart" -> paddingStart = parseDpValue(value, baseFontSizeSp)
            "paddingRight", "paddingInlineEnd" -> paddingEnd = parseDpValue(value, baseFontSizeSp)
            "paddingTop", "paddingBlockStart" -> paddingTop = parseDpValue(value, baseFontSizeSp)
            "paddingBottom", "paddingBlockEnd" -> paddingBottom = parseDpValue(value, baseFontSizeSp)
            "paddingBlock" -> {
                val (top, bottom) = parseLogicalPair(value, baseFontSizeSp)
                if (top != null) paddingTop = top
                if (bottom != null) paddingBottom = bottom
            }
            "paddingInline" -> {
                val (start, end) = parseLogicalPair(value, baseFontSizeSp)
                if (start != null) paddingStart = start
                if (end != null) paddingEnd = end
            }
            "margin" -> {
                val (top, right, bottom, left) = parseFourValue(value, baseFontSizeSp)
                if (top != null) marginTop = top
                if (right != null) marginEnd = right
                if (bottom != null) marginBottom = bottom
                if (left != null) marginStart = left
            }
            "marginLeft", "marginInlineStart" -> marginStart = parseMarginValue(value, baseFontSizeSp)
            "marginRight", "marginInlineEnd" -> marginEnd = parseMarginValue(value, baseFontSizeSp)
            "marginTop", "marginBlockStart" -> marginTop = parseMarginValue(value, baseFontSizeSp)
            "marginBottom", "marginBlockEnd" -> marginBottom = parseMarginValue(value, baseFontSizeSp)
            "borderInlineStart" -> if (value.isNotBlank() && value != "none" && value != "0") {
                leftAccent = true
                hasBorder = true
                borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
            }
            // Opacity / textAlign etc. handled above; remaining keys fall through safely.
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
        opacity = opacity,
        textAlign = textAlign,
        verticalAlign = verticalAlign,
        whiteSpace = whiteSpace,
        wordBreak = wordBreak,
        fitContent = fitContent,
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
    val rawValue = raw.substring(colonIndex + 1).trim()
    val value = if (rawValue.endsWith("!important", ignoreCase = true)) {
        rawValue.dropLast("!important".length).trim()
    } else {
        rawValue
    }
    if (key == "list-style") {
        // Shorthand: expand rather than store, so the longhand readers see what CSS defines.
        expandListStyle(value, out)
        return
    }
    if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
}

/** Returns the index of the `}` matching the `{` at [openBraceIndex], or -1 if unbalanced. */private fun findMatchingBraceEnd(css: String, openBraceIndex: Int): Int {
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
        val attrStart = selectorPart.indexOf("[data-sc", i)
        if (attrStart == -1) break
        // Bound the search INSIDE this bracket: `[a][b="v"]` must not leak `v`
        // from the second attribute into the first.
        val closeBracket = selectorPart.indexOf(']', attrStart)
        if (closeBracket == -1) break
        // Support attribute operators: `=` exact, `^=` prefix, `$=` suffix, `*=` substring,
        // `~=` word. All selectors key on the literal data-sc-* value.
        val equalsIndex = selectorPart.indexOf('=', attrStart)
        if (equalsIndex == -1 || equalsIndex > closeBracket) {
            i = closeBracket + 1
            continue
        }
        val operator = selectorPart.getOrNull(equalsIndex - 1)?.takeIf { it == '^' || it == '$' || it == '*' || it == '~' }
        val attrEnd = if (operator != null) equalsIndex - 1 else equalsIndex
        val attrName = selectorPart.substring(attrStart + 1, attrEnd)
        if (!attrName.startsWith("data-sc")) {
            i = closeBracket + 1
            continue
        }
        val valueStart = equalsIndex + 1
        if (valueStart >= selectorPart.length) break
        val quote = selectorPart[valueStart]
        if (quote != '\'' && quote != '"') {
            i = closeBracket + 1
            continue
        }
        val valueEnd = selectorPart.indexOf(quote, valueStart + 1)
        if (valueEnd == -1 || valueEnd > closeBracket) {
            i = closeBracket + 1
            continue
        }
        val value = selectorPart.substring(valueStart + 1, valueEnd)
        if (value.isNotEmpty()) result.add(value)
        i = valueEnd + 1
    }
    return result
}

private val CLASS_SELECTOR_REGEX = Regex("""\.([a-zA-Z][a-zA-Z0-9_-]*)""")
/**
 * Extracts simple class selectors (`.sense`, `.glossary`) that dicts like Jitendex use.
 * Keys are namespaced with a "." prefix so they can never collide with data-sc values.
 * Selector parts containing combinators (`>`, `+`, `~`), pseudo-classes (`:hover`, `:not`),
 * or descendant spaces are skipped — flattening those would leak styles onto every node.
 */
private fun extractClassSelectors(selectorPart: String): List<String> {
    // strip attribute-bracket contents so `[data-sc-class="x.y"]` doesn't yield ".y"
    val stripped = buildString {
        var depth = 0
        for (ch in selectorPart) {
            when (ch) {
                '[' -> { depth++; append(' ') }
                ']' -> { depth--; append(' ') }
                else -> append(if (depth > 0) ' ' else ch)
            }
        }
    }
    return splitSelectorList(stripped)
        .map { it.trim() }
        .filter { part ->
            part.isNotEmpty() && part.none { ch ->
                ch == '>' || ch == '+' || ch == '~' || ch == ':' || ch == ' ' || ch == '\t'
            }
        }
        .flatMap { CLASS_SELECTOR_REGEX.findAll(it).map { m -> m.groupValues[1] } }
        .map { ".$it" } // namespace so class keys never collide with data-sc values
        .filter { it.length > 1 }
}

/**
 * Extracts attribute-presence selectors (`[data-sc-meaning]`, `[data-sc-logo][data-sc-round]`)
 * from a rule's selector list. Returns one key-set per comma-separated selector. Structural
 * selectors (descendant/sibling combinators, `:first-child`, `:has(...)`, ...) are skipped: those
 * target a different element or a specific position we can't model, so flattening them would leak
 * styles onto every matching node. Value-based `[data-sc-content="x"]` selectors are handled by
 * [extractDataSelectors] and ignored here.
 */
private fun extractPresenceSelectors(selectorPart: String): List<Set<String>> {
    if (!selectorPart.contains("[data-sc")) return emptyList()
    val result = mutableListOf<Set<String>>()
    for (selector in splitSelectorList(selectorPart)) {
        val trimmed = selector.trim()
        if (trimmed.isEmpty() || !isSimplePresenceSelector(trimmed)) continue
        val keys = mutableSetOf<String>()
        var i = 0
        while (true) {
            val attrStart = trimmed.indexOf("[data-sc", i)
            if (attrStart == -1) break
            val attrEnd = trimmed.indexOf(']', attrStart)
            if (attrEnd == -1) break
            val attrBody = trimmed.substring(attrStart + 1, attrEnd)
            if (!attrBody.contains('=')) {
                val key = attrBody.removePrefix("data-sc").removePrefix("-")
                if (key.isNotEmpty()) keys.add(key)
            }
            i = attrEnd + 1
        }
        if (keys.isNotEmpty()) result.add(keys)
    }
    return result
}

/** Splits a comma-separated selector list on top-level (depth-0) commas. */
private data class SiblingCompound(val left: String, val right: String, val adjacent: Boolean)

/**
 * Splits a compound on a top-level `+` (adjacent) or `~` (general sibling), ignoring any inside
 * `[...]`. Returns null when the compound has no sibling combinator.
 */
private fun splitSiblingCombinator(part: String): SiblingCompound? {
    var depth = 0
    var split = -1
    var adjacent = true
    for (i in part.indices) {
        when (part[i]) {
            '[' -> depth++
            ']' -> depth--
            '+' -> if (depth == 0) {
                split = i
                adjacent = true
            }
            '~' -> if (depth == 0) {
                split = i
                adjacent = false
            }
        }
        if (split >= 0) break
    }
    if (split < 0) return null
    val left = part.substring(0, split).trim()
    val right = part.substring(split + 1).trim()
    if (left.isEmpty() || right.isEmpty()) return null
    return SiblingCompound(left, right, adjacent)
}

/**
 * Describes one side of a sibling combinator. A compound may be a presence-only attribute selector
 * (`[data-sc-title2]`), a valued attribute (`[data-sc-content="sense-group"]`), a class (`.tag`) or
 * any conjunction of those - so all of them are collected rather than only the first.
 */
private data class CompoundKey(val presences: List<String>, val lookupKeys: List<String>)

private fun compoundKeyOf(compound: String): CompoundKey {
    val presenceNames = mutableListOf<String>()
    val valueKeys = mutableListOf<String>()
    for (m in ATTR_SELECTOR_REGEX.findAll(compound)) {
        // Node attribute maps are keyed the way `extractPresenceSelectors` normalises them:
        // `data-sc-foo` -> `foo`, a bare `data-foo` -> `foo`.
        val raw = m.groupValues[1]
        val name = when {
            raw.startsWith("data-sc-") -> raw.removePrefix("data-sc-")
            raw.startsWith("data-") -> raw.removePrefix("data-")
            else -> raw
        }
        val value = m.groupValues[2]
        // A valued attribute contributes its VALUE as the lookup key, exactly like [getCssStyles];
        // only a valueless one is a presence requirement.
        if (value.isEmpty()) presenceNames.add(name) else valueKeys.add(value)
    }
    for (m in CLASS_SELECTOR_REGEX.findAll(compound)) {
        valueKeys.add("." + m.groupValues[1])
    }
    return CompoundKey(presenceNames, valueKeys)
}

private fun CompoundKey.matches(data: Map<String, String>): Boolean {
    if (presences.isEmpty() && lookupKeys.isEmpty()) return false
    val keys = lookupKeysOf(data)
    return presences.all { it in data.keys } && (lookupKeys.isEmpty() || lookupKeys.any { it in keys })
}

private val ATTR_SELECTOR_REGEX = Regex("""\[([\w-]+)(?:=["']?([^"'\]]*)["']?)?]""")

/**
 * Styles from an `A + B` / `A ~ B` rule, applied when a preceding sibling matched A.
 *
 * [prevData] is the `data-*` map of the immediately preceding in-flow sibling, or the most recent
 * earlier sibling for a general-sibling rule; null means there was no preceding sibling.
 */
/**
 * The lookup keys an element's `data-*` attributes contribute, in the same shape the parser indexes
 * rules under.
 */
private fun lookupKeysOf(dataAttributes: Map<String, String>): List<String> {
    val keys = mutableListOf<String>()
    for ((k, v) in dataAttributes) {
        if (k == "class") {
            // Class tokens resolve against BOTH ".name" (real class rules from
            // extractClassSelectors) and bare "name" ([data-sc-class="name"] rules) so
            // either selector style matches.
            v.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.forEach { token ->
                keys.add(".$token")
                keys.add(token)
            }
        } else if (v.isNotBlank()) {
            keys.add(v)
        }
    }
    return keys
}

fun getSiblingStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
    prevSiblingData: List<Map<String, String>>,
): Map<String, String> {
    if (prevSiblingData.isEmpty() || parsedCss.siblingRules.isEmpty()) return emptyMap()
    var merged: MutableMap<String, String>? = null
    for (rule in parsedCss.siblingRules) {
        if (!rule.matchesSelf(dataAttributes)) continue
        // `+` only looks at the immediately preceding sibling; `~` at any earlier one.
        val candidates = if (rule.adjacent) prevSiblingData.take(1) else prevSiblingData
        if (candidates.none { rule.matchesPrev(it) }) continue
        if (merged == null) merged = HashMap(rule.styles)
        else merged.putAll(rule.styles)
    }
    return merged ?: emptyMap()
}

private fun splitSelectorList(selectorPart: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    for (i in selectorPart.indices) {
        when (selectorPart[i]) {
            '[' -> depth++
            ']' -> depth--
            ',' -> if (depth == 0) {
                parts.add(selectorPart.substring(start, i))
                start = i + 1
            }
        }
    }
    parts.add(selectorPart.substring(start))
    return parts
}

/**
 * True when a selector is a plain attribute-presence selector usable by the structured renderer:
 * it references `data-sc-*` attributes only (optionally with an element prefix such as `td`) and
 * contains no combinators (`>`, `+`, `~`, descendant space) or pseudo-classes.
 */
private fun isSimplePresenceSelector(selector: String): Boolean {
    if (!selector.contains("[data-sc")) return false
    var depth = 0
    for (ch in selector) {
        when (ch) {
            '[' -> depth++
            ']' -> depth--
            else -> if (depth == 0) {
                if (ch == '>' || ch == '+' || ch == '~' || ch == ':' || ch == '.' || ch == '#') return false
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return false
            }
        }
    }
    return true
}

private val WHITESPACE_REGEX = Regex("\\s+")

/** Extracts a usable color from `color-mix(in srgb, X 5%, transparent)`, `var(...)`, or plain colors. */
private val COLOR_MIX_REGEX = Regex("""color-mix\(in\s+srgb,\s*(.+),\s*(?:transparent|black|var\([^)]+\)|[^)]+)\)""", RegexOption.IGNORE_CASE)
private val HEX_COLOR_REGEX = Regex("""#[0-9a-fA-F]{3,8}""")
private val VAR_FALLBACK_REGEX = Regex("""var\(--[^,)]+,\s*([^)]+)\)""")
private val NAMED_COLOR_REGEX = Regex("""[a-zA-Z#][a-zA-Z0-9#]*""")

internal fun extractBackgroundColor(value: String): String? {
    val trimmed = value.trim()
    // Proper blending first: `color-mix(in srgb, C1 5%, transparent)` → faint tint hex.
    // Without this a 5% tint resolved to the raw first color (near-black solid box).
    if (trimmed.contains("color-mix", ignoreCase = true)) {
        blendColorMix(trimmed)?.let { return it }
        val mixInner = extractColorMixFirstPart(trimmed)
        if (mixInner != null) {
            val inner = mixInner.trim()
            HEX_COLOR_REGEX.findAll(inner).firstOrNull()?.value?.let { return it }
            val varMatch = VAR_FALLBACK_REGEX.find(inner)
            if (varMatch != null) {
                val fallback = varMatch.groupValues[1].trim().removeSuffix(")")
                if (fallback.startsWith("#")) return fallback
            }
            NAMED_COLOR_REGEX.find(inner)?.value?.let { candidate ->
                return candidate.removeSuffix(")").takeIf { it != "transparent" }
            }
            // Fallback: try regex-based extraction for older patterns
        }
    }
    val mixMatch = COLOR_MIX_REGEX.find(trimmed)
    if (mixMatch != null) {
        val inner = mixMatch.groupValues[1].trim()
        // `var(--text-color, var(--fg, #333))` → grab first hex seen
        HEX_COLOR_REGEX.findAll(inner).firstOrNull()?.value?.let { return it }
        val varMatch = VAR_FALLBACK_REGEX.find(inner)
        if (varMatch != null) {
            val fallback = varMatch.groupValues[1].trim().removeSuffix(")")
            if (fallback.startsWith("#")) return fallback
        }
        // Named color such as `green`, `goldenrod`, `#1A73E8`
        NAMED_COLOR_REGEX.find(inner)?.value?.let { candidate ->
            return candidate.removeSuffix(")").takeIf { it != "transparent" }
        }
        return null
    }
    val directVar = VAR_FALLBACK_REGEX.find(trimmed)
    if (directVar != null) {
        val fallback = directVar.groupValues[1].trim().removeSuffix(")")
        if (fallback.startsWith("#")) return fallback
    }
    // `var(--text-color, var(--fg, #333))` (nested fallback) — grab first hex seen anywhere.
    if (trimmed.contains("var(")) {
        HEX_COLOR_REGEX.find(trimmed)?.value?.let { return it }
    }
    // Shorthand border values like `solid 0.3em #FFCCCC` — grab the hex token if present.
    HEX_COLOR_REGEX.find(trimmed)?.value?.let { return it }
    // Named colors (`goldenrod`, `brown`, `crimson`, `lime`...) — parseCssColor2 resolves them.
    if (namedCssColor.containsKey(trimmed.lowercase())) return trimmed
    trimmed.split(WHITESPACE_REGEX)
        .map { it.trim().trimEnd(';').lowercase() }
        .lastOrNull { namedCssColor.containsKey(it) }
        ?.let { return it }
    return trimmed.takeIf { it.startsWith("#") }
}

/**
 * Extracts the first color argument from a `color-mix(in srgb, <c1> <pct>, <c2>)` expression.
 * Handles second arg as transparent / black / var(--bg) etc. and first arg containing
 * var() with nested commas by depth-aware comma splitting.
 */
private fun extractColorMixFirstPart(value: String): String? {
    val lower = value.lowercase()
    val mixIndex = lower.indexOf("color-mix")
    if (mixIndex == -1) return null
    val open = value.indexOf('(', mixIndex)
    if (open == -1) return null
    var depth = 0
    var close = -1
    for (i in open until value.length) {
        when (value[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) { close = i; break }
            }
        }
    }
    if (close == -1) return null
    val inner = value.substring(open + 1, close) // e.g. "in srgb, var(--tag-color) 80%, black"
    // Remove leading "in srgb," prefix
    val afterSrbg = if (inner.contains("in srgb", ignoreCase = true)) {
        inner.substringAfter("in srgb", "").let { s ->
            // drop first comma after srgb
            val comma = s.indexOf(',')
            if (comma != -1) s.substring(comma + 1) else s
        }
    } else inner
    // Split at top-level comma (depth 0) separating two colors
    var d = 0
    var splitAt = -1
    for (i in afterSrbg.indices) {
        when (afterSrbg[i]) {
            '(' -> d++
            ')' -> d--
            ',' -> if (d == 0) { splitAt = i; break }
        }
    }
    val firstPart = if (splitAt != -1) afterSrbg.substring(0, splitAt) else afterSrbg
    return firstPart.trim().ifEmpty { null }
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

/**
 * Expands the `list-style` shorthand into its longhands.
 *
 * CSS 2.1 12.5.1: `list-style: <list-style-type> || <list-style-position> || <list-style-image>`. Each
 * component is optional and may appear in any order, and `none` resets its component. Oxford writes
 * `li[data-sc-content="hypo-item"] { list-style: circle }`, so without this the marker silently fell
 * back to the `disc` the UA stylesheet defaults to - a visible difference on every hypo list.
 */
private fun expandListStyle(value: String, out: MutableMap<String, String>) {
    val positions = setOf("inside", "outside")
    for (token in value.trim().split(WHITESPACE_REGEX)) {
        when {
            token.isEmpty() -> Unit
            token in positions -> out["list-style-position"] = token
            token.startsWith("url(") -> out["list-style-image"] = token
            // A bare marker keyword: disc, circle, square, decimal, none, or any of the string forms.
            else -> out["list-style-type"] = token
        }
    }
}

/**
 * Parses a CSS 1-4 value box shorthand (`padding`/`margin`) into (top, right, bottom, left)
 * as dp. `1em` → all sides; `0 1em` → vertical 0, horizontal 1em; `1em 0 1em` → top/right/bottom;
 * `1em 2em 3em 4em` → all four.
 */
private fun parseFourValue(value: String, baseFontSizeSp: Float): FourValues {
    val tokens = value.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    val parsed = tokens.mapNotNull { parseMarginValue(it, baseFontSizeSp) }
    return when (parsed.size) {
        1 -> FourValues(parsed[0], parsed[0], parsed[0], parsed[0])
        2 -> FourValues(parsed[0], parsed[1], parsed[0], parsed[1])
        3 -> FourValues(parsed[0], parsed[1], parsed[2], parsed[1])
        4 -> FourValues(parsed[0], parsed[1], parsed[2], parsed[3])
        else -> FourValues(null, null, null, null)
    }
}

private data class FourValues(val top: Float?, val right: Float?, val bottom: Float?, val left: Float?)

/**
 * Parses a 1-2 value logical shorthand (`padding-block`/`padding-inline`) into its two components.
 * `0.1em` → both; `0.1em 0.5em` → first, second.
 */
private fun parseLogicalPair(value: String, baseFontSizeSp: Float): Pair<Float?, Float?> {
    val tokens = value.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    return when (tokens.size) {
        1 -> {
            val v = tokens[0].let { parseDpValue(it, baseFontSizeSp) }
            v to v
        }
        else -> {
            val first = tokens.getOrNull(0)?.let { parseDpValue(it, baseFontSizeSp) }
            val second = tokens.getOrNull(1)?.let { parseDpValue(it, baseFontSizeSp) }
            first to second
        }
    }
}
