package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.Immutable
import java.util.BitSet

/**
 * A CSS rule that matches by attribute *presence* rather than a literal value: `[data-sc-meaning]`
 * or a compound like `[data-sc-logo][data-sc-round]` (all keys required, e.g. Shinmeikai Kokugo).
 */
@Immutable
data class CssPresenceRule(
    val requiredKeys: Set<String>,
    val styles: Map<String, String>,
    val isBox: Boolean,
    /**
     * True when the source selector was a bare single attribute (`[data-sc-X]`), specificity
     * (0,1,0). Such rules lose to base.css structural rules like `.structured-content div`
     * (0,1,1), exactly as the browser computes them, so div margins resolved from them must not
     * override the base default. Anything else (multi-attribute compounds, tag- or
     * class-qualified selectors) is strong.
     */
    val bare: Boolean = false,
    /** Leading element tag (`table`, `td`, …), or null when the selector had none. */
    val tag: String? = null,
)

/** One attribute-presence selector, with its element prefix when it has one. */
private data class PresenceSelector(val keys: Set<String>, val bare: Boolean, val tag: String?)

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
    /**
     * Sibling position required of the element: 0 = `:first-child`, -1 = `:last-child`, n =
     * `:nth-child(n)`. Null when the selector names no position. Only the table cell path can
     * satisfy one; see [positionalMatches].
     */
    val positional: Int? = null,
    val styles: Map<String, String> = emptyMap(),
    val isBox: Boolean = false,
    /** Ordered nesting scopes; each compound must match a single ancestor. */
    val scopes: List<CssAncestorSelector> = emptyList(),
    val target: CssCompound? = null,
)

/** One ancestor frame used for [NestedTagRule] matching. */
@Immutable
data class CssAncestor(
    /** Lookup keys of the ancestor's data (`content` values, class tokens …). */
    val values: Set<String> = emptySet(),
    /** Ancestor element tag, lowercase (`div`, `td`, …). */
    val tag: String = "",
    /** 1-based position among siblings, when known (synthetic `tr` frames). */
    val siblingIndex: Int? = null,
    val isLastSibling: Boolean = false,
)

/** One simple compound in a `:has()` selector or its ancestor chain. */
@Immutable
data class CssCompound(
    val tag: String = "",
    val values: Set<String> = emptySet(),
    val presences: Set<String> = emptySet(),
    val has: List<CssRelativeSelector> = emptyList(),
    val positional: Int? = null,
    val requiresOpen: Boolean = false,
    val excluded: List<CssCompound> = emptyList(),
    val exclusionSpecificity: Long = 0L,
)

/** A relative selector inside `:has(...)`; descendant and child only. */
@Immutable
data class CssRelativeSelector(val compound: CssCompound, val direct: Boolean = false)

/** One ancestor compound in a `:has()` rule; [direct] is the combinator from the previous one. */
@Immutable
data class CssAncestorSelector(val compound: CssCompound, val direct: Boolean = false)

/** A selector with `:has()`; kept out of the flat attribute index so it cannot leak. */
@Immutable
data class CssHasRule(
    val target: CssCompound,
    val ancestors: List<CssAncestorSelector> = emptyList(),
    val targetDirect: Boolean = false,
    val styles: Map<String, String> = emptyMap(),
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
    /**
     * Value-keyed selectors written exclusively by bare single-attribute selectors
     * (`[data-sc-X]`, `[data-sc-X="v"]`, `.cls`): specificity (0,1,0). Declarations from these
     * lose to base.css structural rules like `.structured-content div` (0,1,1) - see
     * [getStrongFlatStyles]. A key with any compound/tag-qualified writer is absent here.
     */
    val weakValueKeys: Set<String> = emptySet(),
    /**
     * Custom-property values in scope when this sheet was parsed: base.css `:root`, overridden
     * by the caller's theme variables (the same values the WebView injects), overridden by this
     * stylesheet's own `:root`. Used to resolve `var()` at parse time; anything unresolvable is
     * left verbatim for the runtime fallbacks.
     */
    val variables: Map<String, String> = emptyMap(),
    /** Selectors with `:has()`, matched against the element tree at compile time. */
    val hasRules: List<CssHasRule> = emptyList(),
    /** Plain type selectors, applied before the more specific attribute/class rules. */
    val tagStyles: Map<String, Map<String, String>> = emptyMap(),
) {
    internal val nativeDeclarations by lazy { NativeCssRuleDeclarations(this) }
    internal val nativeSiblingSelectors by lazy { NativeSiblingSelectors(siblingRules) }
    private val qualifiedIndex = selectorStyles.entries.filter { '\u0000' in it.key }
        .groupBy { it.key.substringBefore('\u0000') }
        .mapValues { (_, entries) -> entries.associate { it.key.substringAfter('\u0000') to it.value } }
    fun qualifiedStyles(tag: String?): Map<String, Map<String, String>> = qualifiedIndex[tag] ?: emptyMap()
    private val presenceIndex = presenceRules.withIndex().groupBy { it.value.requiredKeys.firstOrNull() }
    public val hasWeakFlatStyles = tagStyles.isNotEmpty() || weakValueKeys.isNotEmpty() || presenceRules.any { it.bare }
    // Sort once per sheet, not per element. Stable sorting preserves source order for ties.
    private val nestedCascade = nestedTagRules.sortedBy { it.specificity() }
    private val wildcardTags = nestedCascade.filter { it.tag.isEmpty() }
    private val tagIndex = nestedTagRules.map { it.tag }.filter { it.isNotEmpty() }.distinct().associateWith { tag ->
        nestedCascade.filter { it.tag.isEmpty() || it.tag == tag }
    }
    private val nestedIndex = NestedRuleIndex(nestedCascade)
    internal val ancestorMachine = NativeCssAncestorMachine(nestedCascade)

    internal fun nestedCandidates(tag: String, data: Map<String, String>, keys: List<String>, into: BitSet): BitSet =
        nestedIndex.candidates(tag, data, keys, into)

    internal fun nestedRule(index: Int): IndexedNestedRule = nestedIndex.rules[index]

    internal fun presenceCandidates(data: Map<String, String>, into: BitSet): BitSet {
        into.clear()
        presenceIndex[null]?.forEach { into.set(it.index) }
        for (key in data.keys) presenceIndex[key]?.forEach { into.set(it.index) }
        return into
    }

    public fun presenceCandidates(data: Map<String, String>): List<CssPresenceRule> {
        if (presenceRules.isEmpty()) return emptyList()
        val candidates = mutableListOf<IndexedValue<CssPresenceRule>>()
        presenceIndex[null]?.let { candidates.addAll(it) }
        for (key in data.keys) presenceIndex[key]?.let { candidates.addAll(it) }
        candidates.sortBy { it.index }
        return candidates.map { it.value }
    }

    public fun tagCandidates(tag: String): List<NestedTagRule> = tagIndex[tag] ?: wildcardTags

    /** True if any rule (value-, presence- or tag-based) carries box semantics. */
    val hasBoxRules: Boolean
        get() = boxSelectors.isNotEmpty() || presenceRules.any { it.isBox } ||
            nestedTagRules.any { it.isBox }

    companion object {
        val EMPTY = ParsedCss()
    }
}

/** Ordered ancestor selectors advance once at tree entry, not by backtracking for every target. */
internal class NativeCssAncestorMachine(rules: List<NestedTagRule>) {
    internal class State(val reachable: BitSet, val here: BitSet)
    private data class Step(val previous: Int, val compound: Int, val direct: Boolean)
    /** Tag and one required key have already been established by the dispatch index. */
    private class Residual(val source: CssCompound, val keys: Array<String>) {
        fun matches(values: Set<String>, tag: String, node: StructuredNode.Element?, index: Int?, last: Boolean): Boolean {
            if (source.requiresOpen && node?.attributes?.properties?.get("open") != "true") return false
            for (excluded in source.excluded) if (excluded.matchesFrame(values, tag, node, index, last)) return false
            for (key in keys) if (key !in values) return false
            if (source.positional != null && !positionalMatches(source.positional, index, last)) return false
            if (source.has.isNotEmpty()) {
                if (node == null) return false
                for (relative in source.has) if (!hasDescendant(node, relative)) return false
            }
            return true
        }
    }
    private val compounds = ArrayList<CssCompound>()
    private val residuals = ArrayList<Residual?>()
    private val steps = ArrayList<Step>()
    private val positions = ArrayList<MutableList<Int>>()
    private val ends = IntArray(rules.size) { -1 }
    private val required = Array(rules.size) { IntArray(0) }
    private val values = HashMap<String, BitSet>()
    private val tags = HashMap<String, BitSet>()
    private val unanchored = BitSet()
    private val wildcardTags = BitSet()
    private val emptyBits = BitSet()
    val root = State(emptyBits, emptyBits)

    init {
        val compoundIds = HashMap<CssCompound, Int>()
        val stepIds = HashMap<Step, Int>()
        fun compoundId(compound: CssCompound): Int = compoundIds.getOrPut(compound) {
            compounds.size.also { compounds.add(compound); positions.add(ArrayList()) }
        }
        fun stepId(step: Step): Int = stepIds.getOrPut(step) {
            steps.size.also { steps.add(step); positions[step.compound].add(it) }
        }
        for ((rank, rule) in rules.withIndex()) {
            var previous = -1
            for (scope in rule.scopes) {
                previous = stepId(Step(previous, compoundId(scope.compound), scope.direct))
            }
            ends[rank] = previous
            if (rule.scopes.isEmpty()) required[rank] = buildList {
                for (key in rule.ancestors) add(stepId(Step(-1, compoundId(CssCompound(values = setOf(key))), false)))
                rule.parentTag?.let { add(stepId(Step(-1, compoundId(CssCompound(tag = it)), false))) }
            }.toIntArray()
        }
        for ((id, compound) in compounds.withIndex()) {
            if (compound.tag.isEmpty()) wildcardTags.set(id) else tags.getOrPut(compound.tag, ::BitSet).set(id)
            val anchor = compound.values.firstOrNull() ?: compound.presences.firstOrNull()?.let { BARE_KEY_MARKER + it }
            if (anchor == null) unanchored.set(id) else values.getOrPut(anchor, ::BitSet).set(id)
            val remaining = buildList {
                for (value in compound.values) if (value != anchor) add(value)
                for (presence in compound.presences) {
                    val key = BARE_KEY_MARKER + presence
                    if (key != anchor) add(key)
                }
            }
            residuals.add(if (remaining.isEmpty() && !compound.requiresOpen && compound.excluded.isEmpty() &&
                compound.positional == null && compound.has.isEmpty()) null else Residual(compound, remaining.toTypedArray()))
        }
        for (bucket in tags.values) bucket.or(wildcardTags)
    }

    fun advance(parent: State, frameValues: Set<String>, tag: String, node: StructuredNode.Element?,
        siblingIndex: Int?, isLast: Boolean, candidates: BitSet): State {
        if (steps.isEmpty()) return root
        candidates.clear()
        candidates.or(unanchored)
        for (key in frameValues) values[key]?.let(candidates::or)
        candidates.and(tags[tag] ?: wildcardTags)
        var here: BitSet? = null
        var compound = candidates.nextSetBit(0)
        while (compound >= 0) {
            if (residuals[compound]?.matches(frameValues, tag, node, siblingIndex, isLast) != false) {
                for (position in positions[compound]) {
                    val step = steps[position]
                    if (step.previous < 0 || (if (step.direct) parent.here else parent.reachable)[step.previous]) {
                        val matches = here ?: BitSet().also { here = it }
                        matches.set(position)
                    }
                }
            }
            compound = candidates.nextSetBit(compound + 1)
        }
        val current = here ?: return if (parent.here.isEmpty) parent else State(parent.reachable, emptyBits)
        var position = current.nextSetBit(0)
        var addsReachable = false
        while (position >= 0) {
            if (!parent.reachable[position]) { addsReachable = true; break }
            position = current.nextSetBit(position + 1)
        }
        val reachable = if (addsReachable) (parent.reachable.clone() as BitSet).also { it.or(current) } else parent.reachable
        return State(reachable, current)
    }

    fun matches(state: State, rank: Int, direct: Boolean): Boolean {
        val end = ends[rank]
        return if (end < 0) required[rank].all { state.reachable[it] }
            else (if (direct) state.here else state.reachable)[end]
    }
}

/** Necessary conditions only: ordered scope/position/:has matching remains authoritative. */
internal class IndexedNestedRule(val rule: NestedTagRule) {
    val specificity = rule.specificity()
    val ancestorKeys: Set<String> = if (rule.scopes.isEmpty()) rule.ancestors else buildSet {
        for (scope in rule.scopes) {
            addAll(scope.compound.values)
            for (key in scope.compound.presences) add(BARE_KEY_MARKER + key)
        }
    }
    val ancestorTags: Set<String> = if (rule.scopes.isEmpty()) setOfNotNull(rule.parentTag) else
        rule.scopes.mapNotNullTo(mutableSetOf()) { it.compound.tag.takeIf(String::isNotEmpty) }
}

/** Each rule has one target anchor and one global cascade rank; queries allocate no candidate list. */
private class NestedRuleIndex(cascade: List<NestedTagRule>) {
    val rules = cascade.map(::IndexedNestedRule)
    private val unanchored = BitSet()
    private val presences = HashMap<String, BitSet>()
    private val values = HashMap<String, BitSet>()
    private val attributes = HashMap<String, MutableMap<String, BitSet>>()
    private val wildcardTags = BitSet()
    private val tags = HashMap<String, BitSet>()

    init {
        for ((rank, indexed) in rules.withIndex()) {
            val rule = indexed.rule
            if (rule.tag.isEmpty()) wildcardTags.set(rank) else tags.getOrPut(rule.tag, ::BitSet).set(rank)
            val qualifier = rule.qualifier.entries.firstOrNull()
            when {
                qualifier?.key == "class" && qualifier.value.isNotEmpty() -> values.getOrPut(".${qualifier.value}", ::BitSet).set(rank)
                qualifier != null && qualifier.value.isNotEmpty() ->
                    attributes.getOrPut(qualifier.key) { HashMap() }.getOrPut(qualifier.value, ::BitSet).set(rank)
                qualifier != null -> presences.getOrPut(qualifier.key, ::BitSet).set(rank)
                !rule.target?.values.isNullOrEmpty() -> values.getOrPut(rule.target.values.first(), ::BitSet).set(rank)
                !rule.target?.presences.isNullOrEmpty() -> presences.getOrPut(rule.target.presences.first(), ::BitSet).set(rank)
                else -> unanchored.set(rank)
            }
        }
        for (tag in tags.values) tag.or(wildcardTags)
    }

    fun candidates(tag: String, data: Map<String, String>, keys: List<String>, into: BitSet): BitSet {
        into.clear()
        into.or(unanchored)
        for ((key, value) in data) {
            presences[key]?.let(into::or)
            attributes[key]?.get(value)?.let(into::or)
        }
        for (key in keys) values[key]?.let(into::or)
        into.and(tags[tag] ?: wildcardTags)
        return into
    }
}

// The supported selector subset has no IDs: attributes/classes/pseudo-classes outrank types.
// :has() contributes its argument's specificity, not an additional pseudo-class weight.
private fun NestedTagRule.specificity(): Long {
    val ancestorWeight = if (scopes.isNotEmpty()) scopes.sumOf { it.compound.specificity() }
        else (ancestors.size.toLong() shl 32) + (if (parentTag != null) 1L else 0L)
    return ancestorWeight + (target?.specificity()
        ?: (((qualifier.size.toLong() + (if (positional != null) 1L else 0L)) shl 32) +
            (if (tag.isNotEmpty()) 1L else 0L)))
}

private fun CssCompound.specificity(): Long =
    ((values.size.toLong() + presences.size + (if (positional != null) 1L else 0L) +
        (if (requiresOpen) 1L else 0L)) shl 32) +
        (if (tag.isNotEmpty()) 1L else 0L) + has.sumOf { it.compound.specificity() } +
        exclusionSpecificity

/**
 * `base.css` `:root` custom properties (base.css:1-40), the light-mode values the reference
 * resolves when nothing overrides them. The WebView overrides the theme-sensitive subset at
 * runtime ([DictionaryBootstrapHtml] `dynamicThemeCss`); callers pass those overrides as
 * [themeVars] so both engines compute identical colors. Tag colors and other literals are
 * mode-independent.
 */
public val BASE_CSS_VARIABLES: Map<String, String> = mapOf(
    "bg" to "#ffffff",
    "fg" to "#1f1f1f",
    "secondary" to "#757575",
    "accent" to "#2ecc71",
    "on-accent" to "#ffffff",
    "border" to "rgba(0, 0, 0, 0.08)",
    "hover-bg" to "rgba(0, 0, 0, 0.04)",
    "font-size-no-units" to "14",
    "font-size" to "calc(1px * var(--font-size-no-units))",
    "line-height" to "1.4",
    "lookup-tabs-height" to "0px",
    "headword-font-size" to "1.6em",
    "tag-font-size" to "0.75em",
    "thin-border-size" to "1px",
    "tag-default-background-color" to "#8a8a91",
    "tag-name-background-color" to "#b6327a",
    "tag-expression-background-color" to "#f0ad4e",
    "tag-popular-background-color" to "#0275d8",
    "tag-frequent-background-color" to "#5bc0de",
    "tag-archaism-background-color" to "#d9534f",
    "tag-dictionary-background-color" to "#aa66cc",
    "tag-part-of-speech-background-color" to "#565656",
    "tag-search-background-color" to "#8a8a91",
    "tag-pronunciation-dictionary-background-color" to "#6640be",
    "tag-dialect-background-color" to "#2b70b4",
    "tag-style-background-color" to "#69696e",
    "background-color" to "var(--bg)",
    "text-color" to "var(--fg)",
    "entry-vertical-padding" to "0.5em",
    "entry-horizontal-padding" to "0.5em",
)

/**
 * Custom properties declared in plain `:root { --x: v; }` blocks. Scoped variants
 * (`:root[attr]`, `:root.foo`) belong to other modes/states and are skipped: the oracle runs
 * light mode, and device dark mode resolves through [themeVars] instead.
 */
public fun parseCssVariables(cssText: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    // Plain `:root {` only: `:root[` / `:root.` / `:root:` variants address other states.
    val rootRule = Regex("""(?:^|[}\s;])\s*:root\s*\{([^}]*)\}""")
    for (match in rootRule.findAll(cssText)) {
        for (decl in match.groupValues[1].split(';')) {
            val colon = decl.indexOf(':')
            if (colon == -1) continue
            val name = decl.take(colon).trim()
            if (!name.startsWith("--")) continue
            val value = decl.substring(colon + 1).trim()
            if (value.isNotEmpty()) out[name.removePrefix("--")] = value
        }
    }
    return out
}

/**
 * Resolves `var()` references in a declaration value against [variables], following CSS
 * custom-property semantics: a defined variable wins (recursively), otherwise its fallback,
 * otherwise the reference is unresolvable. Returns the original string unless the result is
 * fully resolved (no `var(` remains) and contains no `calc(` (never evaluated here): partial
 * results would corrupt the runtime fallback extraction, so those keep the original text.
 */
public fun resolveCssVarRefs(value: String, variables: Map<String, String>, depth: Int = 0): String {
    if (!value.contains("var(") || depth > 10) return value
    val start = value.indexOf("var(")
    var d = 0
    var end = -1
    var j = start + 3
    while (j < value.length) {
        when (value[j]) {
            '(' -> d++
            ')' -> {
                d--
                if (d == 0) {
                    end = j
                    break
                }
            }
        }
        j++
    }
    if (end == -1) return value
    val inner = value.substring(start + 4, end)
    var dd = 0
    var split = -1
    for (k in inner.indices) {
        when (inner[k]) {
            '(' -> dd++
            ')' -> dd--
            ',' -> if (dd == 0) {
                split = k
                break
            }
        }
    }
    val name = (if (split == -1) inner else inner.take(split)).trim().removePrefix("--")
    val fallback = if (split == -1) "" else inner.substring(split + 1)
    // A defined-but-unresolvable variable is "invalid at computed-value time": CSS falls back
    // rather than keeping the outer reference, so an unresolvable definition delegates to the
    // fallback instead of aborting the whole declaration.
    val replacement = if (variables.containsKey(name)) {
        val resolvedDef = resolveCssVarRefs(variables.getValue(name), variables, depth + 1)
        if (resolvedDef.contains("var(") && fallback.isNotEmpty()) {
            resolveCssVarRefs(fallback, variables, depth + 1)
        } else {
            resolvedDef
        }
    } else if (fallback.isNotEmpty()) {
        resolveCssVarRefs(fallback, variables, depth + 1)
    } else {
        return value
    }
    if (replacement.contains("var(")) return value
    val resolved = value.replaceRange(start, end + 1, replacement)
    if (resolved.contains("var(")) return resolveCssVarRefs(resolved, variables, depth + 1)
    if (resolved.contains("calc(")) return value
    return resolved
}

/**
 * Parses a dictionary's CSS text into [ParsedCss]. Never throws.
 *
 * Parsing is cached on the CSS text itself. A stylesheet only changes when its dictionary is
 * updated or reinstalled, which changes the text and so misses the cache on its own - there is no
 * separate invalidation to get wrong. This matters because [buildParsedCssMap] runs inside
 * `produceState` on every lookup, so without this every lookup re-parsed every enabled
 * dictionary's stylesheet.
 *
 * @param themeVars theme-driven variable values for this render (the same values the WebView
 * injects: accent/fg/bg/etc. computed from the active color scheme). They override base.css
 * defaults and are overridden by the stylesheet's own `:root`, exactly the document order
 * (base, dynamic theme, dictionary styles). Empty in the harness oracle, where no injection
 * happens and both engines resolve base.css lights.
 */
fun parseDictionaryCss(cssText: String?, themeVars: Map<String, String> = emptyMap()): ParsedCss {
    if (cssText.isNullOrBlank()) return ParsedCss.EMPTY
    // Theme variables change the computed declarations, so they join the cache key: a light parse
    // must never serve a dark render.
    //
    // The key is a composite, NOT a concatenated string. Concatenating built
    // `cssText + "\0" + themeVars.toString()`, which allocated a fresh copy of the whole stylesheet
    // (tens of KB) and then hashed every character of it, on every call. A stylesheet is static
    // across lookups, so `String.hashCode()` is already cached on the instance and the theme map
    // is tiny: hashing those two directly is O(1) instead of O(stylesheet length).
    val cacheKey = CssCacheKey(cssText, themeVars)
    cssCache[cacheKey]?.let { return it }
    val parsed = parseDictionaryCssUncached(cssText, themeVars)
    // A user can enable many dictionaries; the cap only guards against unbounded growth if
    // stylesheet keys are rewritten repeatedly. Dropping everything is fine, just re-parses.
    if (cssCache.size >= CSS_CACHE_MAX_ENTRIES) cssCache.clear()
    cssCache[cacheKey] = parsed
    return parsed
}

/** Cache key for a stylesheet plus its theme variables. Hashes in O(1) rather than over the sheet. */
private class CssCacheKey(private val text: String, private val theme: Map<String, String>) {
    private val hash = 31 * text.hashCode() + theme.hashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean =
        other is CssCacheKey && text == other.text && theme == other.theme
}

private const val CSS_CACHE_MAX_ENTRIES = 64

private val cssCache = java.util.concurrent.ConcurrentHashMap<CssCacheKey, ParsedCss>()

private fun parseDictionaryCssUncached(cssText: String, themeVars: Map<String, String> = emptyMap()): ParsedCss {
    val cleaned = stripCssComments(cssText)
    if (cleaned.isBlank()) return ParsedCss.EMPTY
    // Document order: base.css, injected theme, this stylesheet. The caller's theme variables
    // (the same values the WebView injects) override base defaults; this sheet's own `:root`
    // overrides both.
    val variables = BASE_CSS_VARIABLES + themeVars + parseCssVariables(cleaned)

    val boxSelectors = mutableSetOf<String>()
    val selectorStyles = mutableMapOf<String, MutableMap<String, String>>()
    val tagStyles = mutableMapOf<String, MutableMap<String, String>>()
    val siblingRules = mutableListOf<SiblingRule>()
    val presenceRules = mutableListOf<CssPresenceRule>()
    val nestedTagRules = mutableListOf<NestedTagRule>()
    val hasRules = mutableListOf<CssHasRule>()
    // Specificity provenance for the flat value-keyed index: keys written exclusively by bare
    // single-attribute selectors lose to base.css structural rules and are withheld from div
    // margins (see [getStrongFlatStyles]). A key with any compound/tag-qualified/class-combined
    // writer stays strong even if bare rules also wrote it.
    val bareValueKeys = mutableSetOf<String>()
    val strongValueKeys = mutableSetOf<String>()

    var i = 0
    while (i < cleaned.length) {
        val braceStart = cleaned.indexOf('{', i)
        if (braceStart == -1) break
        val selectorPart = cleaned.substring(i, braceStart).trim()
        // Find the matching close brace, accounting for nested `& ... {}` blocks.
        val braceEnd = findMatchingBraceEnd(cleaned, braceStart)
        if (braceEnd == -1) break
        val propertiesPart = cleaned.substring(braceStart + 1, braceEnd)
        val properties = parseProperties(propertiesPart, variables)
        // Both of these were recomputed several times per rule. `splitSelectorList` allocated a
        // fresh list on each call, and `toCamelCase` was applied once per (selector x property)
        // rather than once per property. Neither is observable, they are just pure recomputation.
        val rawParts = splitSelectorList(selectorPart)
        val beforeParts = rawParts.filter { it.trim().endsWith("::before") }
        val selectorParts = rawParts.filterNot { it in beforeParts }
        if (beforeParts.isNotEmpty()) {
            val generated = properties.mapKeys { generatedProperty(it.key) }.toMutableMap()
            properties["content"]?.let { generated["beforeContent"] = generatedContent(it) }
            for (part in beforeParts) {
                parseDescendantCompound(part.trim().removeSuffix("::before"), generated, allowSingle = true)
                    ?.let(nestedTagRules::add)
            }
        }
        val hasParts = selectorParts.filter { it.contains(":has(") }
        val normalParts = if (hasParts.isEmpty()) selectorParts else selectorParts.filterNot { it.contains(":has(") }
        val camelProperties = properties.mapKeys { toCamelCase(it.key) }
        if (camelProperties.isNotEmpty()) {
            for (part in hasParts) parseHasRule(part, camelProperties)?.let { hasRules.add(it) }
        }

        val presence = extractPresenceSelectors(normalParts.joinToString(","))
        // Sibling combinators `A + B` (adjacent) and `A ~ B` (general). Both are kept as rules keyed
        // on B, carrying the requirement that a preceding sibling match A, and are resolved by
        // [getSiblingStyles] once the caller knows what actually precedes the node in the flow.
        // Restricting this to `A + A` - as an earlier revision did - silently dropped every
        // cross-compound rule in the dictionary stylesheets.
        normalParts.forEach { part ->
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
                    styles = camelProperties,
                ),
            )
        }
// Compound selectors (`[a][b="v"]`, `.x.y`) target a conjunction we can't flatten:
        // indexing either value alone would style every node matching just one side. Counted per
        // selector so one compound in a list doesn't disable the plain selectors beside it.
        // Bareness travels with the part (not the flattened key list) so specificity provenance
        // below can tell bare `[data-sc-X]` writers from compound ones.
        val partKeys: List<Pair<Boolean, List<String>>> = normalParts.map { part ->
            val bare = isBareSingleAttrSelector(part)
            val compound = parseCompound(part)
            if (compound == null || compound.positional != null || compound.requiresOpen || compound.excluded.isNotEmpty()) return@map bare to emptyList()
            val keys = if (part.count { it == '[' } <= 1) {
                extractDataSelectors(part) + extractClassSelectors(part)
            } else {
                extractClassSelectors(part)
            }
            // Tag-qualified values must not leak from e.g. li[content=sense] onto div[sense].
            bare to if (compound.tag.isNotEmpty()) {
                keys.map { compound.tag + '\u0000' + it }
            } else keys
        }
        val allValueSelectors = partKeys.flatMap { it.second }
        for (part in normalParts) {
            val compound = parseCompound(part) ?: continue
            if (compound.tag.isNotEmpty() && part.trim().equals(compound.tag, ignoreCase = true)) {
                tagStyles.getOrPut(compound.tag) { mutableMapOf() }.putAll(camelProperties)
            }
        }
        val nestingScopes = (if ('&' in propertiesPart) normalParts else emptyList()).mapNotNull { part ->
            val compounds = splitCompounds(part) ?: return@mapNotNull null
            val scopes = ArrayList<CssAncestorSelector>(compounds.size)
            for ((raw, combinator) in compounds) {
                if (combinator == '+' || combinator == '~') return@mapNotNull null
                val compound = parseCompound(raw) ?: return@mapNotNull null
                scopes.add(CssAncestorSelector(compound, combinator == '>'))
            }
            scopes.takeIf { it.isNotEmpty() }
        }
        if ((allValueSelectors.isEmpty() && presence.isEmpty()) || properties.isEmpty()) {
            // Even when the flat rule is unusable, descendant compounds
            // (`tr[x] th`) and nested `& tag` blocks may still yield tag rules.
            for (scopes in nestingScopes) parseNestedBlocks(propertiesPart, scopes, nestedTagRules, variables)
            for (part in normalParts) {
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
            camelProperties.forEach { (key, value) ->
                existing[key] = value
            }
        }
        for ((bare, keys) in partKeys) {
            (if (bare) bareValueKeys else strongValueKeys).addAll(keys)
        }
        for (selector in presence) {
            presenceRules.add(
                CssPresenceRule(
                    requiredKeys = selector.keys,
                    styles = camelProperties,
                    isBox = hasBoxProperty,
                    bare = selector.bare,
                    tag = selector.tag,
                ),
            )
        }
        // Nested `& tag { ... }` blocks (CSS nesting): tag-scoped rules whose
        // ancestors are this rule's own selector keys.
        for (scopes in nestingScopes) parseNestedBlocks(propertiesPart, scopes, nestedTagRules, variables)
        // Descendant compounds (`A sel tag`, `A > tag`): same shape as nesting.
        for (part in normalParts) {
            parseDescendantCompound(part, properties)?.let { nestedTagRules.add(it) }
        }
        i = braceEnd + 1
    }

    return ParsedCss(
        boxSelectors,
        selectorStyles,
        siblingRules,
        presenceRules,
        nestedTagRules,
        weakValueKeys = bareValueKeys - strongValueKeys,
        variables = variables,
        hasRules = hasRules,
        tagStyles = tagStyles,
    )
}

/**
 * Merges all CSS rules that match an element's `data-*` attribute values.
 */
public class MatchedFlatStyles(val declarations: Map<String, String>, val strong: Map<String, String>)
private val EMPTY_FLAT_STYLES = MatchedFlatStyles(emptyMap(), emptyMap())

/** True when the selector's optional element prefix is satisfied by [tag]. */
private fun CssPresenceRule.appliesTo(tag: String?): Boolean =
    this.tag == null || (tag != null && this.tag.equals(tag, ignoreCase = true))

/** One matching order for the full cascade and its strong-only tier; inlined at callers. */
private inline fun forEachFlatStyle(
    data: Map<String, String>,
    sheet: ParsedCss,
    tag: String?,
    keys: List<String>,
    presenceBits: BitSet? = null,
    consume: (Map<String, String>, Boolean) -> Unit,
) {
    sheet.tagStyles[tag]?.let { consume(it, false) }
    if (data.isEmpty() && sheet.presenceRules.isEmpty()) return
    for (key in keys) {
        val styles = sheet.selectorStyles[key] ?: continue
        consume(styles, key !in sheet.weakValueKeys)
    }
    val qualified = sheet.qualifiedStyles(tag)
    if (qualified.isNotEmpty()) for (key in keys) {
        qualified[key]?.let { consume(it, true) }
    }
    if (presenceBits != null) {
        val candidates = sheet.presenceCandidates(data, presenceBits)
        var rank = candidates.nextSetBit(0)
        while (rank >= 0) {
            val rule = sheet.presenceRules[rank]
            if (rule.appliesTo(tag) && data.keys.containsAll(rule.requiredKeys)) consume(rule.styles, !rule.bare)
            rank = candidates.nextSetBit(rank + 1)
        }
    } else {
        for (rule in sheet.presenceCandidates(data)) {
            if (rule.appliesTo(tag) && data.keys.containsAll(rule.requiredKeys)) {
                consume(rule.styles, !rule.bare)
            }
        }
    }
}

/** Match both cascade tiers together; div margins must exclude bare-attribute rules. */
public fun matchFlatStyles(
    data: Map<String, String>,
    sheet: ParsedCss,
    includeStrong: Boolean = true,
    tag: String? = null,
): MatchedFlatStyles {
    if (data.isEmpty() && sheet.presenceRules.isEmpty() && sheet.tagStyles[tag].isNullOrEmpty()) return EMPTY_FLAT_STYLES
    val split = includeStrong && sheet.hasWeakFlatStyles
    var all: MutableMap<String, String>? = null
    var strong: MutableMap<String, String>? = null
    forEachFlatStyle(data, sheet, tag, lookupKeysOf(data)) { declarations, isStrong ->
        val allTarget = all
        if (allTarget == null) all = HashMap(declarations) else allTarget.putAll(declarations)
        if (split && isStrong) {
            val strongTarget = strong
            if (strongTarget == null) strong = HashMap(declarations) else strongTarget.putAll(declarations)
        }
    }
    val declarations = all ?: emptyMap()
    return MatchedFlatStyles(declarations, if (includeStrong && !split) declarations else strong ?: emptyMap())
}

fun getCssStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
    tag: String? = null,
    structuralMargins: Boolean = false,
): Map<String, String> = collectFlatStyles(dataAttributes, parsedCss, tag, strongOnly = false,
    structuralMargins = structuralMargins)

/**
 * Flat styles minus bare-single-attribute contributions (specificity (0,1,0)), which lose to
 * base.css structural rules like `.structured-content div` (0,1,1) - verified against Chrome,
 * which computes `margin: 4px 0` on those divs even when a bare `[data-sc-X]` margin rule
 * matches. Same merge order as [getCssStyles]; bare presence rules and exclusively-bare
 * value keys are withheld. Everything else (compounds, tag-qualified selectors, sibling and
 * nested rules are resolved separately) keeps current behavior.
 */
fun getStrongFlatStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
    tag: String? = null,
): Map<String, String> = collectFlatStyles(dataAttributes, parsedCss, tag, strongOnly = true)

private fun collectFlatStyles(
    data: Map<String, String>,
    sheet: ParsedCss,
    tag: String?,
    strongOnly: Boolean,
    structuralMargins: Boolean = false,
): Map<String, String> {
    var merged: MutableMap<String, String>? = null
    forEachFlatStyle(data, sheet, tag, lookupKeysOf(data)) { styles, isStrong ->
        if (!strongOnly || isStrong) {
            merged = appendCssDeclarations(merged, styles, structuralMargins && !isStrong)
        }
    }
    return merged ?: emptyMap()
}

private fun appendCssDeclarations(
    target: MutableMap<String, String>?,
    styles: Map<String, String>,
    skipMargins: Boolean = false,
): MutableMap<String, String>? {
    if (styles.isEmpty()) return target
    if (!skipMargins) return (target ?: HashMap()).apply { putAll(styles) }
    var destination = target
    for ((key, value) in styles) if (!key.startsWith("margin")) {
        if (destination == null) destination = HashMap()
        destination[key] = value
    }
    return destination
}

/**
 * Marker prefix distinguishing bare attribute-name keys from data values inside ancestor
 * requirement sets and [CssAncestor] frames. Without it a key like `meaning` would match a rule
 * ancestor meant as the VALUE `meaning` (`[data-sc-content="meaning"]`) and vice versa.
 */
private const val BARE_KEY_MARKER = "\u0000key:"

/** Parsed attribute facts, independent of stylesheet, ancestors, width and rendered words. */
internal class CssAttributeKeys(val values: List<String>, val ancestors: Set<String>)

private val EMPTY_ATTRIBUTE_KEYS = CssAttributeKeys(emptyList(), emptySet())

internal fun cssAttributeKeys(data: Map<String, String>): CssAttributeKeys {
    if (data.isEmpty()) return EMPTY_ATTRIBUTE_KEYS
    val values = lookupKeysOf(data)
    val ancestors = LinkedHashSet<String>(data.size + values.size)
    for (key in data.keys) ancestors.add(BARE_KEY_MARKER + key)
    ancestors.addAll(values)
    return CssAttributeKeys(values, ancestors)
}

/**
 * Lookup keys for an element's data map: `content` values plus class tokens
 * (as both `.name` and bare `name`, mirroring [getCssStyles]). Bare attribute NAMES are included
 * under [BARE_KEY_MARKER] so presence-style ancestor requirements (`table[data-sc-X]` scoping a
 * `td` rule) match the way the browser's presence matching does. Used ONLY to build [CssAncestor]
 * frames, never for flat value matching (see [lookupKeysOf]).
 */
public fun dataValueKeys(dataAttributes: Map<String, String>): Set<String> = cssAttributeKeys(dataAttributes).ancestors

/**
 * Merges [NestedTagRule] styles matching an element ([tag], lowercase) with the
 * given ancestor chain. Candidates are ordered by specificity, then source order.
 */
public fun getNestedStyles(
    tag: String,
    dataAttributes: Map<String, String>,
    ancestors: List<CssAncestor>,
    parsedCss: ParsedCss,
    /**
     * 1-based position of this element among its siblings, for `:first-child` / `:nth-child(n)` /
     * `:last-child` rules. Null (the default) means "no position known", which makes every
     * positional rule inapplicable rather than wrongly applicable.
     */
    siblingIndex: Int? = null,
    isLastSibling: Boolean = false,
): Map<String, String> {
    if (parsedCss.nestedTagRules.isEmpty() || tag.isEmpty()) return emptyMap()
    val candidates = parsedCss.nestedCandidates(tag, dataAttributes, lookupKeysOf(dataAttributes), BitSet())
    if (candidates.isEmpty()) return emptyMap()
    val values = ancestors.flatMapTo(mutableSetOf()) { it.values }
    val ancestorTags = ancestors.mapTo(mutableSetOf()) { it.tag }
    val parent = ancestors.lastOrNull()
    var merged: MutableMap<String, String>? = null
    var rank = candidates.nextSetBit(0)
    while (rank >= 0) {
        val indexed = parsedCss.nestedRule(rank)
        rank = candidates.nextSetBit(rank + 1)
        if (!values.containsAll(indexed.ancestorKeys) || !ancestorTags.containsAll(indexed.ancestorTags)) continue
        val rule = indexed.rule
        if (rule.tag.isNotEmpty() && rule.tag != tag) continue
        if (rule.qualifier.isNotEmpty() && !qualifierMatches(rule.qualifier, dataAttributes)) continue
        if (rule.target?.matchesTarget(tag, dataAttributes, null, siblingIndex, isLastSibling) == false) continue
        if (rule.positional != null && !positionalMatches(rule.positional, siblingIndex, isLastSibling)) continue
        if (rule.scopes.isNotEmpty()) {
            if (!matchesNestingScopes(rule.scopes, rule.direct, ancestors.lastIndex) { compound, index ->
                    val frame = ancestors[index]
                    compound.matchesFrame(frame.values, frame.tag, null, frame.siblingIndex, frame.isLastSibling)
                }) continue
        } else if (rule.direct) {
            if (parent == null || !parent.values.containsAll(rule.ancestors)) continue
            if (rule.parentTag != null && parent.tag != rule.parentTag) continue
        } else {
            if (!values.containsAll(rule.ancestors)) continue
            if (rule.parentTag != null) {
                if (!ancestorTags.contains(rule.parentTag)) continue
            }
        }
        if (merged == null) merged = HashMap(rule.styles) else merged.putAll(rule.styles)
    }
    return merged ?: emptyMap()
}

/**
 * Native ancestor context. Selector-prefix bitsets advance at entry and the parent state is
 * restored at exit; no repeated ancestor searches or key/tag membership-count updates.
 * Frames remain available for :has() and APIs that need actual ancestor nodes.
 */
public class NativeCssContext internal constructor(private val parsedCss: ParsedCss, private val cascade: NativeCssCascade) {
    constructor(parsedCss: ParsedCss) : this(parsedCss, NativeCssCascade())

    private val compiled = parsedCss.nativeDeclarations
    private val hasDeclarations = parsedCss.tagStyles.isNotEmpty() || parsedCss.selectorStyles.isNotEmpty() ||
        parsedCss.presenceRules.isNotEmpty() || parsedCss.siblingRules.isNotEmpty() ||
        parsedCss.nestedTagRules.isNotEmpty() || parsedCss.hasRules.isNotEmpty()
    private val enabled = parsedCss.nestedTagRules.isNotEmpty() || parsedCss.hasRules.isNotEmpty()
    private class Frame(
        val values: Set<String>,
        val tag: String,
        val node: StructuredNode.Element?,
        val siblingIndex: Int?,
        val isLast: Boolean,
        val selectors: NativeCssAncestorMachine.State,
    )

    private val frames = ArrayList<Frame>()
    private val nestedCandidates = BitSet()
    private val presenceCandidates = BitSet()
    private val siblingCandidates = BitSet()
    private val ancestorCandidates = BitSet()
    private val ancestorMachine = parsedCss.ancestorMachine

    fun push(node: StructuredNode.Element) {
        if (!enabled) return
        pushFrame(node.attributes.cssKeys.ancestors, node.tag.tagName, node, null, false)
    }

    fun push(values: Set<String>, tag: String) {
        if (!enabled) return
        pushFrame(values, tag, null, null, false)
    }

    fun push(values: Set<String>, tag: String, siblingIndex: Int?, isLast: Boolean) {
        if (!enabled) return
        pushFrame(values, tag, null, siblingIndex, isLast)
    }

    private fun pushFrame(values: Set<String>, tag: String, node: StructuredNode.Element?, siblingIndex: Int?, isLast: Boolean) {
        val started = if (cascade.collectStageTimings) System.nanoTime() else 0L
        val selectors = ancestorMachine.advance(frames.lastOrNull()?.selectors ?: ancestorMachine.root,
            values, tag, node, siblingIndex, isLast, ancestorCandidates)
        frames.add(Frame(values, tag, node, siblingIndex, isLast, selectors))
        if (cascade.collectStageTimings) cascade.ancestorNanos += System.nanoTime() - started
    }

    fun pop() {
        if (!enabled) return
        if (frames.isEmpty()) return
        frames.removeAt(frames.lastIndex)
    }

    /** Snapshot for APIs that still take a list (table cell CSS). */
    fun ancestors(): List<CssAncestor> = frames.map {
        CssAncestor(it.values, it.tag, it.siblingIndex, it.isLast)
    }

    fun hasStyles(node: StructuredNode.Element): Map<String, String> {
        var merged: MutableMap<String, String>? = null
        forEachHasStyle(node) { merged = appendCssDeclarations(merged, it) }
        return merged ?: emptyMap()
    }

    private inline fun forEachHasStyle(node: StructuredNode.Element, consume: (Map<String, String>) -> Unit) {
        if (parsedCss.hasRules.isEmpty()) return
        val data = node.attributes.data
        for (rule in parsedCss.hasRules) {
            if (!rule.target.matchesTarget(node.tag.tagName, data, node, null, false)) continue
            if (!matchesAncestors(rule)) continue
            consume(rule.styles)
        }
    }

    private fun matchesAncestors(rule: CssHasRule): Boolean {
        var prev = -1
        for (anc in rule.ancestors) {
            var found = -1
            var i = prev + 1
            while (i < frames.size) {
                if (anc.direct && prev >= 0 && i != prev + 1) break
                val f = frames[i]
                if (anc.compound.matchesFrame(f.values, f.tag, f.node, f.siblingIndex, f.isLast)) {
                    found = i
                    break
                }
                i++
            }
            if (found == -1) return false
            prev = found
        }
        if (rule.targetDirect && (prev == -1 || prev != frames.lastIndex)) return false
        return true
    }

    private fun tableFlatState(tag: String, data: Map<String, String>, keys: List<String>,
        inherited: NativeCssCascade.State = cascade.root): NativeCssCascade.State {
        var state = inherited
        // Table defaults are separate from the ordinary div/universal reset policy.
        forEachFlatStyle(data, parsedCss, tag, keys, presenceCandidates) { styles, _ ->
            state = state.append(compiled.block(styles))
        }
        return state
    }

    internal fun tableRowInheritance(data: Map<String, String>): NativeCssRuleBlock =
        cascade.tableRowInheritance(tableFlatState("tr", data, lookupKeysOf(data)).resolve(cascade))

    internal fun tableRowDeclarations(node: StructuredNode.Element): NativeCssDeclarations {
        val attributes = node.attributes
        var state = tableFlatState("tr", attributes.data, attributes.cssKeys.values)
        forEachNestedStyle("tr", attributes.data, null, false, attributes.cssKeys.values) { styles, _ ->
            state = state.append(compiled.block(styles))
        }
        if (attributes.style.isNotEmpty()) state = state.append(cascade.inline(attributes.nativeStyle))
        return state.resolve(cascade)
    }

    internal fun tableCellDeclarations(cell: StructuredTableCell, inherited: NativeCssRuleBlock): NativeCssDeclarations {
        val tag = if (cell.isHeader) "th" else "td"
        val attributes = cell.element?.attributes ?: StructuredAttributes.Empty
        val position = cell.indexInSourceRow + 1
        val last = cell.indexInSourceRow == cell.sourceRowSize - 1
        var state = tableFlatState(tag, attributes.data, attributes.cssKeys.values, cascade.root.append(inherited))
        forEachNestedStyle(tag, attributes.data, position, last, attributes.cssKeys.values) { styles, _ ->
            state = state.append(compiled.block(styles))
        }
        for (rule in parsedCss.hasRules) {
            if (rule.target.matchesTarget(tag, attributes.data, cell.element, position, last) && matchesAncestors(rule))
                state = state.append(compiled.block(rule.styles))
        }
        if (attributes.style.isNotEmpty()) state = state.append(cascade.inline(attributes.nativeStyle))
        return state.resolve(cascade)
    }

    fun nestedStyles(
        tag: String,
        dataAttributes: Map<String, String>,
        siblingIndex: Int? = null,
        isLastSibling: Boolean = false,
    ): Map<String, String> {
        var merged: MutableMap<String, String>? = null
        forEachNestedStyle(tag, dataAttributes, siblingIndex, isLastSibling) { styles, _ ->
            merged = appendCssDeclarations(merged, styles)
        }
        return merged ?: emptyMap()
    }

    /** Match selectors normally, then follow compiled declaration transitions shared by the worker. */
    fun declarations(node: StructuredNode.Element, preceding: List<Map<String, String>> = emptyList()): NativeCssDeclarations =
        declarations(node, preceding, null)

    internal fun declarations(node: StructuredNode.Element, siblings: NativeCssSiblings): NativeCssDeclarations =
        declarations(node, emptyList(), siblings)

    private fun declarations(node: StructuredNode.Element, preceding: List<Map<String, String>>,
        siblings: NativeCssSiblings?): NativeCssDeclarations {
        if (!hasDeclarations) return if (node.attributes.style.isEmpty()) NativeCssDeclarations.EMPTY
            else cascade.intern(node.attributes.nativeStyle)
        var state = cascade.root
        var typeRule = parsedCss.tagStyles[node.tag.tagName]
        forEachFlatStyle(node.attributes.data, parsedCss, node.tag.tagName, node.attributes.cssKeys.values, presenceCandidates) { styles, strong ->
            state = state.append(compiled.block(styles, node.tag == StructuredTag.Div && !strong,
                reset = typeRule === styles))
            typeRule = null
        }
        if (siblings != null) {
            val candidates = siblings.candidates(node.attributes, siblingCandidates)
            var rank = candidates.nextSetBit(0)
            while (rank >= 0) {
                state = state.append(compiled.block(parsedCss.siblingRules[rank].styles))
                rank = candidates.nextSetBit(rank + 1)
            }
        } else {
            forEachSiblingStyle(node.attributes.data, parsedCss, preceding) { state = state.append(compiled.block(it)) }
        }
        forEachNestedStyle(node.tag.tagName, node.attributes.data, null, false, node.attributes.cssKeys.values, node) { styles, specificity ->
            // Shipping resets: .structured-content * (0,1,0), div margins (0,1,1).
            state = state.append(compiled.block(styles,
                structural = node.tag == StructuredTag.Div && specificity < (1L shl 32) + 1,
                reset = specificity < (1L shl 32)))
        }
        forEachHasStyle(node) { state = state.append(compiled.block(it)) }
        if (node.attributes.style.isNotEmpty()) state = state.append(cascade.inline(node.attributes.nativeStyle))
        return state.resolve(cascade)
    }

    private inline fun forEachNestedStyle(
        tag: String,
        dataAttributes: Map<String, String>,
        siblingIndex: Int?,
        isLastSibling: Boolean,
        keys: List<String> = lookupKeysOf(dataAttributes),
        element: StructuredNode.Element? = null,
        consume: (Map<String, String>, Long) -> Unit,
    ) {
        if (parsedCss.nestedTagRules.isEmpty() || tag.isEmpty()) return
        val candidates = parsedCss.nestedCandidates(tag, dataAttributes, keys, nestedCandidates)
        if (candidates.isEmpty()) return
        val parent = frames.lastOrNull()
        var rank = candidates.nextSetBit(0)
        while (rank >= 0) {
            val indexed = parsedCss.nestedRule(rank)
            val candidateRank = rank
            rank = candidates.nextSetBit(rank + 1)
            val rule = indexed.rule
            if (!ancestorMachine.matches(parent?.selectors ?: ancestorMachine.root, candidateRank,
                    rule.scopes.isNotEmpty() && rule.direct)) continue
            if (rule.tag.isNotEmpty() && rule.tag != tag) continue
            if (rule.qualifier.isNotEmpty() && !qualifierMatches(rule.qualifier, dataAttributes)) continue
            if (rule.target?.matchesTarget(tag, dataAttributes, element, siblingIndex, isLastSibling) == false) continue
            if (rule.positional != null && !positionalMatches(rule.positional, siblingIndex, isLastSibling)) continue
            if (rule.scopes.isEmpty() && rule.direct) {
                // Child combinator: the DIRECT parent carries the ancestor values.
                if (parent == null || !parent.values.containsAll(rule.ancestors)) continue
                if (rule.parentTag != null && parent.tag != rule.parentTag) continue
            }
            consume(rule.styles, indexed.specificity)
        }
    }
}

private fun qualifierMatches(qualifier: Map<String, String>, data: Map<String, String>): Boolean =
    qualifier.all { (k, v) ->
        if (k == "class") {
            data[k]?.split(WHITESPACE_REGEX)?.any { it == v } == true
        } else if (v.isEmpty()) {
            // A presence attribute (`[data-sc-caption]`): the key alone has to be there.
            data.containsKey(k)
        } else {
            data[k] == v
        }
    }

private fun matchesNestingScopes(
    scopes: List<CssAncestorSelector>,
    direct: Boolean,
    last: Int,
    matches: (CssCompound, Int) -> Boolean,
): Boolean {
    fun find(scopeIndex: Int, end: Int, childOnly: Boolean): Boolean {
        if (scopeIndex < 0) return true
        if (end < 0) return false
        val scope = scopes[scopeIndex]
        var index = end
        while (index >= 0) {
            if (matches(scope.compound, index) && find(scopeIndex - 1, index - 1, scope.direct)) return true
            if (childOnly) break
            index--
        }
        return false
    }
    return find(scopes.lastIndex, last, direct)
}

/**
 * Scans a rule body for nested `& <selector> { ... }` blocks (CSS nesting) and
 * records them as [NestedTagRule]s scoped to [scopes]. Recurses so
 * `& td { ... & span { ... } }` yields a span rule parented under `td`.
 * `&::before`-style pseudo rules are skipped (owned by the beforeContent path).
 */
private fun parseNestedBlocks(
    body: String,
    scopes: List<CssAncestorSelector>,
    out: MutableList<NestedTagRule>,
    variables: Map<String, String> = emptyMap(),
) {
    if (scopes.isEmpty()) return
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
                val props = parseProperties(inner, variables)
                if (props.isNotEmpty()) {
                    out.add(
                        NestedTagRule(
                            ancestors = scopes.flatMapTo(mutableSetOf()) { it.compound.values },
                            parentTag = scopes.last().compound.tag.takeIf { it.isNotEmpty() },
                            direct = direct,
                            tag = tag,
                            qualifier = qualifier,
                            styles = props.mapKeys { toCamelCase(it.key) },
                            isBox = props.keys.any {
                                it.startsWith("background") || it.startsWith("border") ||
                                    it.startsWith("padding") || it.startsWith("margin") ||
                                    it == "clip-path"
                            },
                            scopes = scopes,
                        ),
                    )
                }
                // Recurse: deeper `& x` blocks are parented under this tag (when known).
                val target = parseCompound(sel.trim().removePrefix(">").trim())
                if (target != null) parseNestedBlocks(inner, scopes + CssAncestorSelector(target, direct), out, variables)
            }
        }
        i = end + 1
    }
}

private val NESTED_TAG_SELECTOR_REGEX =
    Regex("""^(?:>\s*)?([a-zA-Z][a-zA-Z0-9]*)?\s*(\[[^\]]+\]|\.[a-zA-Z][a-zA-Z0-9_-]*)?$""")

/** `:first-child`, `:nth-child(n)`, `:last-child` — the position of an element among its siblings. */
private val POSITIONAL_PSEUDO_REGEX = Regex(""":(first-child|last-child|nth-child)\((\d+)\)|:(first-child|last-child)""")

/**
 * A sibling-position constraint: `nth = 0` for `:first-child`, `-1` for `:last-child`.
 *
 * Needed for rules like `td:first-child { background-color: … }` (小学館 使い分け) whose cells were
 * silently dropped before, leaving every cell the base colour. Only the table cell path supplies a
 * position; elsewhere [positionalMatches] returns false and the rule does not apply, which is
 * strictly safer than dropping the whole rule.
 */
private fun positionalConstraint(sel: String): Int? {
    val m = POSITIONAL_PSEUDO_REGEX.find(sel) ?: return null
    // The regex has two alternatives with DIFFERENT capture groups, and reading the wrong one
    // silently yields null - which is exactly how every `td:first-child` rule ended up discarded:
    //   :nth-child(N)  -> group 1 = "nth-child", group 2 = the digits, group 3 = ""
    //   :first-child   -> group 1 = "",        group 2 = "",         group 3 = "first-child"
    val digits = m.groupValues[2].takeIf { it.isNotEmpty() }
    val name = m.groupValues[1].takeIf { it.isNotEmpty() }
        ?: m.groupValues[3].takeIf { it.isNotEmpty() }
        ?: return null
    if (name == "first-child") return 0
    if (name == "last-child") return -1
    return digits?.toIntOrNull()
}

/** True when [index] (1-based) satisfies a constraint from [positionalConstraint]. */
public fun positionalMatches(constraint: Int, index: Int?, isLast: Boolean): Boolean {
    if (index == null) return false
    // 0 encodes `:first-child`, but [index] is 1-based, so the comparison target is 1 there.
    val target = if (constraint < 0) return isLast else if (constraint == 0) 1 else constraint
    return index == target
}

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
        if (eq == -1) {
            // A PRESENCE attribute: `[data-sc-caption]` matches on the key alone. Refusing it
            // here meant no descendant compound with a presence attribute ever parsed, so
            // `table[data-sc-image] td[data-sc-caption] { ... }` - the rule that makes 小学館
            // caption its example-image cells vertically - never reached the cell at all.
            // An empty value is the presence marker; a CSS attribute value cannot be empty.
            val name = qualRaw.substring(1).trim().trimEnd(']').removePrefix("data-sc-")
            if (name.isEmpty()) return null
            qualifier[name] = ""
        } else {
            val name = qualRaw.substring(1, eq).trim().removePrefix("data-sc-")
            val value = qualRaw.substring(eq + 1).trim().trimEnd(']').trim('\'', '"', ' ', '\t')
            if (name.isEmpty() || value.isEmpty()) return null
            qualifier[name] = value
        }
    } else if (qualRaw.startsWith(".")) {
        qualifier["class"] = qualRaw.removePrefix(".")
    }
    return Triple(tag, qualifier, direct)
}

/**
 * Parses a selector containing `:has()` into a [CssHasRule]. Only descendant and child
 * combinators are modeled; anything else is skipped rather than flattened.
 */
private fun parseHasRule(selector: String, styles: Map<String, String>): CssHasRule? {
    if (styles.isEmpty()) return null
    val tokens = splitCompounds(selector) ?: return null
    if (tokens.isEmpty()) return null
    var anyHas = false
    val parsed = ArrayList<Pair<CssCompound, Char>>(tokens.size)
    for ((raw, comb) in tokens) {
        if (comb == '+' || comb == '~') return null
        val compound = parseCompound(raw) ?: return null
        if (compound.has.isNotEmpty()) anyHas = true
        parsed.add(compound to comb)
    }
    if (!anyHas) return null
    val (target, targetComb) = parsed.last()
    val ancestors = parsed.dropLast(1).map { (compound, comb) -> CssAncestorSelector(compound, comb == '>') }
    return CssHasRule(target, ancestors, targetDirect = targetComb == '>', styles = styles)
}

/**
 * Splits a selector at top-level descendant/child combinators. Returns each compound with the
 * combinator that precedes it (space for a descendant, `>` for a child).
 */
private fun splitCompounds(selector: String): List<Pair<String, Char>>? {
    val out = mutableListOf<Pair<String, Char>>()
    var i = 0
    while (i < selector.length) {
        while (i < selector.length && selector[i].isWhitespace()) i++
        if (i >= selector.length) break
        var comb = ' '
        if (selector[i] == '>' || selector[i] == '+' || selector[i] == '~') {
            comb = selector[i]
            i++
            while (i < selector.length && selector[i].isWhitespace()) i++
        }
        val start = i
        var bracket = 0
        var paren = 0
        var quote: Char? = null
        while (i < selector.length) {
            val ch = selector[i]
            if (quote != null) {
                if (ch == quote) quote = null
                i++
                continue
            }
            when (ch) {
                '\'', '"' -> { quote = ch; i++ }
                '[' -> { bracket++; i++ }
                ']' -> { bracket--; i++ }
                '(' -> { paren++; i++ }
                ')' -> { paren--; i++ }
                '>', '+', '~' -> if (bracket == 0 && paren == 0) break else i++
                else -> if (ch.isWhitespace() && bracket == 0 && paren == 0) break else i++
            }
        }
        val compound = selector.substring(start, i).trim()
        if (compound.isEmpty()) return null
        out.add(compound to comb)
    }
    return out
}

/** Parses one compound: tag, data attributes, classes, positional pseudo and `:has()`. */
private fun parseCompound(raw: String): CssCompound? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    var tag = ""
    if (s.startsWith("*")) {
        s = s.substring(1)
    } else if (s[0].isLetter()) {
        var j = 0
        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-')) j++
        tag = s.substring(0, j).lowercase()
        s = s.substring(j)
    }
    val values = mutableSetOf<String>()
    val presences = mutableSetOf<String>()
    val has = mutableListOf<CssRelativeSelector>()
    var positional: Int? = null
    var requiresOpen = false
    val excluded = mutableListOf<CssCompound>()
    var exclusionSpecificity = 0L
    var i = 0
    while (i < s.length) {
        when (val ch = s[i]) {
            '[' -> {
                val end = findClosing(s, i) ?: return null
                val body = s.substring(i + 1, end).trim()
                if (body == "open") {
                    requiresOpen = true
                    i = end + 1
                    continue
                }
                if (!body.startsWith("data-sc")) return null
                val eq = body.indexOf('=')
                if (eq == -1) {
                    val key = body.removePrefix("data-sc").removePrefix("-")
                    if (key.isEmpty()) return null
                    presences.add(key)
                } else {
                    var value = body.substring(eq + 1).trim()
                    if (value.length >= 2 &&
                        ((value.first() == '"' && value.last() == '"') ||
                            (value.first() == '\'' && value.last() == '\''))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    if (value.isEmpty()) return null
                    values.add(value)
                }
                i = end + 1
            }
            '.' -> {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '-')) j++
                if (j == i + 1) return null
                values.add("." + s.substring(i + 1, j))
                i = j
            }
            ':' -> {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '-')) j++
                val name = s.substring(i + 1, j).lowercase()
                when (name) {
                    "not" -> {
                        if (s.getOrNull(j) != '(') return null
                        val end = findClosing(s, j) ?: return null
                        val group = splitSelectorList(s.substring(j + 1, end)).map { parseCompound(it) ?: return null }
                        if (group.isEmpty()) return null
                        excluded.addAll(group)
                        exclusionSpecificity += group.maxOf { it.specificity() }
                        i = end + 1
                    }
                    "has" -> {
                        if (s.getOrNull(j) != '(') return null
                        val end = findClosing(s, j) ?: return null
                        val inner = s.substring(j + 1, end).trim()
                        val rel = splitCompounds(inner) ?: return null
                        if (rel.size != 1) return null
                        val (innerRaw, innerComb) = rel[0]
                        if (innerComb == '+' || innerComb == '~') return null
                        val compound = parseCompound(innerRaw) ?: return null
                        has.add(CssRelativeSelector(compound, innerComb == '>'))
                        i = end + 1
                    }
                    "first-child" -> { positional = 0; i = j }
                    "last-child" -> { positional = -1; i = j }
                    "nth-child" -> {
                        if (s.getOrNull(j) != '(') return null
                        val end = findClosing(s, j) ?: return null
                        positional = s.substring(j + 1, end).trim().toIntOrNull() ?: return null
                        i = end + 1
                    }
                    else -> return null
                }
            }
            else -> return null
        }
    }
    if (tag.isEmpty() && values.isEmpty() && presences.isEmpty() && has.isEmpty() && !requiresOpen && excluded.isEmpty()) return null
    return CssCompound(tag, values, presences, has, positional, requiresOpen, excluded, exclusionSpecificity)
}

/** Index of the `]` / `)` matching the opening bracket at [open], respecting quotes. */
private fun findClosing(s: String, open: Int): Int? {
    val openChar = s[open]
    val closeChar = if (openChar == '[') ']' else ')'
    var depth = 0
    var quote: Char? = null
    for (i in open until s.length) {
        val ch = s[i]
        if (quote != null) {
            if (ch == quote) quote = null
            continue
        }
        if (ch == '"' || ch == '\'') {
            quote = ch
            continue
        }
        if (ch == openChar) depth++
        else if (ch == closeChar) {
            depth--
            if (depth == 0) return i
        }
    }
    return null
}

/**
 * Parses a top-level descendant/child compound (`A sel tag`, `A > tag`) into a
 * [NestedTagRule] carrying [properties]. Reuses the ordered compound matcher,
 * including class-only targets and positional constraints; siblings use their own path.
 */
private fun parseDescendantCompound(part: String, properties: Map<String, String>, allowSingle: Boolean = false): NestedTagRule? {
    val parts = splitCompounds(part.trim()) ?: return null
    if (parts.isEmpty() || parts.any { it.second == '+' || it.second == '~' }) return null
    val scopes = parts.dropLast(1).map { (raw, combinator) ->
        val compound = parseCompound(raw) ?: return null
        CssAncestorSelector(compound, combinator == '>')
    }
    val target = parseCompound(parts.last().first) ?: return null
    if (parts.size == 1 && !allowSingle && !target.requiresOpen && target.excluded.isEmpty()) return null
    if (target.has.isNotEmpty()) return null
    val camel = properties.mapKeys { toCamelCase(it.key) }
    return NestedTagRule(
        direct = parts.last().second == '>',
        tag = target.tag,
        positional = target.positional,
        styles = camel,
        isBox = camel.keys.any {
            it.startsWith("background") || it.startsWith("border") ||
                it.startsWith("padding") || it.startsWith("margin") ||
                it == "clipPath"
        },
        scopes = scopes,
        target = target,
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
    /**
     * Declared `border-width` in dp, or null when the stylesheet does not set one.
     *
     * Previously every border was drawn at one hardcoded thickness, so a dictionary that asked for
     * a 2px separator (小学館 使い分け: `td { border: 2px solid … }`) got the same hairline as a
     * 1px one. The width is also part of a cell's intrinsic size, so this shifts column widths too.
     */
    val borderWidth: Float? = null,
    val backgroundColor: String? = null,
    val opacity: Float? = null,
    val textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    val verticalAlign: String? = null,
    /** `word-break: keep-all` — a CJK cell that must not wrap. Read straight off the cell's CSS map. */
    val wordBreak: String? = null,
    /** `width: fit-content` — shrink-wrap instead of stretching to the parent. */
    val fitContent: Boolean = false,
) {
    /** A left-only border does not separate adjoining block-axis margins (CSS 2.1 8.3.1). */
    internal val separatesVerticalMargins: Boolean get() = hasBorder && !leftAccent

    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null
}

/**
 * Parses a combined style map (already camel-cased by [parseDictionaryCss]) into a [BoxStyle]
 * with dimensions in dp. `defaultFontSizePx` is the base font size used to convert em/rem.
 */
fun parseBoxStyle(styleMap: Map<String, String>, baseFontSizeSp: Float): BoxStyle {
    // The analysis is pure and lives in StyleAnalysis.kt; this is only the Compose-facing
    // adapter, so the renderer never walks a declaration map itself.
    val p = analyzeBoxProps(styleMap, baseFontSizeSp)
    return BoxStyle(
        hasBackground = p.hasBackground,
        hasBorder = p.hasBorder,
        leftAccent = p.leftAccent,
        borderRadius = p.borderRadius,
        paddingStart = p.paddingStart,
        paddingEnd = p.paddingEnd,
        paddingTop = p.paddingTop,
        paddingBottom = p.paddingBottom,
        marginStart = p.marginStart,
        marginEnd = p.marginEnd,
        marginTop = p.marginTop,
        marginBottom = p.marginBottom,
        borderColor = p.borderColor,
        borderWidth = p.borderWidth,
        backgroundColor = p.backgroundColor,
        opacity = p.opacity,
        textAlign = when (p.textAlign) {
            TextAlignValue.START -> androidx.compose.ui.text.style.TextAlign.Start
            TextAlignValue.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
            TextAlignValue.END -> androidx.compose.ui.text.style.TextAlign.End
            TextAlignValue.JUSTIFY -> androidx.compose.ui.text.style.TextAlign.Justify
            null -> null
        },
        verticalAlign = p.verticalAlign,
        wordBreak = p.wordBreak,
        fitContent = p.fitContent,
    )
}

private fun parseProperties(propertiesPart: String, variables: Map<String, String> = emptyMap()): Map<String, String> {
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
                val selector = segment.toString().trim()
                if (selector == "&::before" || selector == "&:before") {
                    for ((name, value) in parseProperties(nested, variables)) {
                        parsed[generatedProperty(name)] = if (name == "content") generatedContent(value) else value
                    }
                }
                segment.clear()
                i = end
            }
            ch == ';' -> {
                addDeclaration(parsed, segment.toString(), variables)
                segment.clear()
            }
            else -> segment.append(ch)
        }
        i++
    }
    if (segment.isNotBlank()) addDeclaration(parsed, segment.toString(), variables)
    return parsed
}

private fun generatedProperty(name: String): String = "before" + toCamelCase(name).replaceFirstChar { it.uppercase() }
private fun generatedContent(value: String): String =
    if (value == "none" || value == "normal") "" else value.trim('"', '\'')

private fun addDeclaration(out: MutableMap<String, String>, raw: String, variables: Map<String, String> = emptyMap()) {
    val colonIndex = raw.indexOf(':')
    if (colonIndex == -1) return
    val key = raw.take(colonIndex).trim().lowercase()
    val rawValue = raw.substring(colonIndex + 1).trim()
    val unimportant = if (rawValue.endsWith("!important", ignoreCase = true)) {
        rawValue.dropLast("!important".length).trim()
    } else {
        rawValue
    }
    // Resolve custom-property references now, so every consumer (colors, box geometry,
    // typography) sees the computed value the browser cascades to - rather than each of them
    // independently re-guessing from fallbacks.
    val value = resolveCssVarRefs(unimportant, variables).trim()
    // Keep calc expressions for the existing property-specific readers; an unresolved plain
    // custom property is invalid at computed-value time, not a usable color or dimension.
    if (value.contains("var(") && !value.contains("calc(")) return
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
private fun extractPresenceSelectors(selectorPart: String): List<PresenceSelector> {
    if (!selectorPart.contains("[data-sc")) return emptyList()
    val result = mutableListOf<PresenceSelector>()
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
        if (keys.isNotEmpty()) {
            val tag = PRESENCE_TAG_REGEX.find(trimmed)?.groupValues?.get(1)?.lowercase()
            result.add(PresenceSelector(keys, isBareSingleAttrSelector(trimmed), tag))
        }
    }
    return result
}

/** Element prefix of a simple presence selector (`table[data-sc-X]` → `table`). */
private val PRESENCE_TAG_REGEX = Regex("""^([a-zA-Z][a-zA-Z0-9]*)\[""")

/**
 * True for selectors with exactly one specificity point and nothing else: a bare attribute
 * (`[data-sc-X]`, `[data-sc-X="v"]`) or a bare class (`.foo`). Specificity (0,1,0), which loses
 * to base.css structural rules like `.structured-content div` (0,1,1). Anything with a tag,
 * a second qualifier, a combinator or a pseudo-class is strong.
 */
private val BARE_ATTR_REGEX = Regex("""^\[data-sc[^]]+\]$""")
private val BARE_CLASS_REGEX = Regex("""^\.[A-Za-z][A-Za-z0-9_-]*$""")
public fun isBareSingleAttrSelector(selector: String): Boolean {
    val trimmed = selector.trim()
    return BARE_ATTR_REGEX.matches(trimmed) || BARE_CLASS_REGEX.matches(trimmed)
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
            // CJK keys concatenate with no hyphen (`data-sc使い分け` -> `使い分け`).
            raw.startsWith("data-sc") -> raw.removePrefix("data-sc").removePrefix("-")
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

private val ATTR_SELECTOR_REGEX = Regex("""\[([^\]\s=]+)(?:=["']?([^"'\]]*)["']?)?]""")

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
    if (dataAttributes.isEmpty()) return emptyList()
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

private fun CssCompound.matchesElement(
    node: StructuredNode.Element,
    siblingIndex: Int? = null,
    isLast: Boolean = false,
): Boolean {
    if (tag.isNotEmpty() && tag != node.tag.tagName) return false
    if (requiresOpen && node.attributes.properties["open"] != "true") return false
    if (excluded.any { it.matchesElement(node, siblingIndex, isLast) }) return false
    val data = node.attributes.data
    if (presences.any { it !in data.keys }) return false
    if (values.isNotEmpty()) {
        val keys = node.attributes.cssKeys.values
        if (!values.all { it in keys }) return false
    }
    if (positional != null && !positionalMatches(positional, siblingIndex, isLast)) return false
    if (has.isNotEmpty() && !has.all { hasDescendant(node, it) }) return false
    return true
}

private fun CssCompound.matchesTarget(
    tag: String,
    data: Map<String, String>,
    element: StructuredNode.Element?,
    siblingIndex: Int?,
    isLast: Boolean,
): Boolean {
    if (this.tag.isNotEmpty() && this.tag != tag) return false
    if (requiresOpen && element?.attributes?.properties?.get("open") != "true") return false
    if (excluded.any { it.matchesTarget(tag, data, element, siblingIndex, isLast) }) return false
    if (presences.any { it !in data.keys }) return false
    if (values.isNotEmpty()) {
        val keys = if (element != null && element.attributes.data === data) element.attributes.cssKeys.values
            else lookupKeysOf(data)
        if (!values.all { it in keys }) return false
    }
    if (positional != null && !positionalMatches(positional, siblingIndex, isLast)) return false
    if (has.isNotEmpty()) {
        if (element == null) return false
        if (!has.all { hasDescendant(element, it) }) return false
    }
    return true
}

private fun CssCompound.matchesFrame(
    frameValues: Set<String>,
    frameTag: String,
    node: StructuredNode.Element?,
    siblingIndex: Int?,
    isLast: Boolean,
): Boolean {
    if (tag.isNotEmpty() && tag != frameTag) return false
    if (requiresOpen && node?.attributes?.properties?.get("open") != "true") return false
    if (excluded.any { it.matchesFrame(frameValues, frameTag, node, siblingIndex, isLast) }) return false
    if (presences.any { (BARE_KEY_MARKER + it) !in frameValues }) return false
    if (values.isNotEmpty() && !values.all { it in frameValues }) return false
    if (positional != null && !positionalMatches(positional, siblingIndex, isLast)) return false
    if (has.isNotEmpty()) {
        if (node == null) return false
        if (!has.all { hasDescendant(node, it) }) return false
    }
    return true
}

private fun hasDescendant(node: StructuredNode.Element, rel: CssRelativeSelector): Boolean {
    if (rel.direct) {
        return node.children.any { it is StructuredNode.Element && rel.compound.matchesElement(it) }
    }
    val stack = java.util.ArrayDeque<StructuredNode>()
    for (child in node.children) stack.addLast(child)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (n is StructuredNode.Element) {
            if (rel.compound.matchesElement(n)) return true
            for (child in n.children) stack.addLast(child)
        }
    }
    return false
}

/** Styles from `:has()` rules for a table cell, whose element is not visited by the main walk. */
public fun getHasStyles(
    tag: String,
    dataAttributes: Map<String, String>,
    element: StructuredNode.Element?,
    ancestors: List<CssAncestor>,
    parsedCss: ParsedCss,
    siblingIndex: Int? = null,
    isLastSibling: Boolean = false,
): Map<String, String> {
    if (parsedCss.hasRules.isEmpty()) return emptyMap()
    var merged: MutableMap<String, String>? = null
    for (rule in parsedCss.hasRules) {
        if (!rule.target.matchesTarget(tag, dataAttributes, element, siblingIndex, isLastSibling)) continue
        if (!matchHasAncestors(rule, ancestors)) continue
        if (merged == null) merged = HashMap(rule.styles) else merged.putAll(rule.styles)
    }
    return merged ?: emptyMap()
}

private fun matchHasAncestors(rule: CssHasRule, ancestors: List<CssAncestor>): Boolean {
    var prev = -1
    for (anc in rule.ancestors) {
        var found = -1
        var i = prev + 1
        while (i < ancestors.size) {
            if (anc.direct && prev >= 0 && i != prev + 1) break
            val a = ancestors[i]
            if (anc.compound.matchesFrame(a.values, a.tag, null, a.siblingIndex, a.isLastSibling)) {
                found = i
                break
            }
            i++
        }
        if (found == -1) return false
        prev = found
    }
    if (rule.targetDirect && (prev == -1 || prev != ancestors.lastIndex)) return false
    return true
}

fun getSiblingStyles(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
    prevSiblingData: List<Map<String, String>>,
): Map<String, String> {
    var merged: MutableMap<String, String>? = null
    forEachSiblingStyle(dataAttributes, parsedCss, prevSiblingData) { merged = appendCssDeclarations(merged, it) }
    return merged ?: emptyMap()
}

private inline fun forEachSiblingStyle(
    dataAttributes: Map<String, String>,
    parsedCss: ParsedCss,
    prevSiblingData: List<Map<String, String>>,
    consume: (Map<String, String>) -> Unit,
) {
    if (prevSiblingData.isEmpty() || parsedCss.siblingRules.isEmpty()) return
    for (rule in parsedCss.siblingRules) {
        if (!rule.matchesSelf(dataAttributes)) continue
        // `+` only looks at the immediately preceding sibling; `~` at any earlier one.
        if (rule.adjacent) {
            if (!rule.matchesPrev(prevSiblingData.first())) continue
        } else if (prevSiblingData.none { rule.matchesPrev(it) }) continue
        consume(rule.styles)
    }
}

private fun splitSelectorList(selectorPart: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var paren = 0
    var quote: Char? = null
    var start = 0
    for (i in selectorPart.indices) {
        val ch = selectorPart[i]
        if (quote != null) {
            if (ch == quote) quote = null
            continue
        }
        when (ch) {
            '\'', '"' -> quote = ch
            '[' -> depth++
            ']' -> depth--
            '(' -> paren++
            ')' -> paren--
            ',' -> if (depth == 0 && paren == 0) {
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

public fun extractBackgroundColor(value: String): String? {
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
public fun toCamelCase(cssProperty: String): String {
    if ('-' !in cssProperty) return cssProperty
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
public fun parseDpValue(value: String, baseFontSizeSp: Float): Float? {
    val trimmed = value.trim().lowercase()
    return when {
        trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()
        trimmed.endsWith("rem") -> trimmed.removeSuffix("rem").toFloatOrNull()?.times(16f)
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
public fun parseMarginValue(value: String, baseFontSizeSp: Float): Float? {
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
public fun parseFourValue(value: String, baseFontSizeSp: Float): FourValues {
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

public data class FourValues(val top: Float?, val right: Float?, val bottom: Float?, val left: Float?)

/**
 * Parses a 1-2 value logical shorthand (`padding-block`/`padding-inline`) into its two components.
 * `0.1em` → both; `0.1em 0.5em` → first, second.
 */
public fun parseLogicalPair(value: String, baseFontSizeSp: Float): Pair<Float?, Float?> {
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
