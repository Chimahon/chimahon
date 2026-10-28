package eu.kanade.tachiyomi.ui.dictionary.compose

import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Property identities are stylesheet facts, independent of document text and layout width. */
internal class NativeCssProperty private constructor(val name: String, val slot: Int) {
    companion object {
        private val names = ConcurrentHashMap<String, NativeCssProperty>()
        private val slots = AtomicInteger()
        fun named(name: String): NativeCssProperty = names.computeIfAbsent(name) { NativeCssProperty(it, slots.getAndIncrement()) }
        fun find(name: String): NativeCssProperty? = names[name]

        @JvmField val Display = named("display")
        @JvmField val Visibility = named("visibility")
        @JvmField val VerticalAlign = named("verticalAlign")
        @JvmField val WhiteSpace = named("whiteSpace")
        @JvmField val TextSpacingTrim = named("textSpacingTrim")
        @JvmField val BeforeContent = named("beforeContent")
        @JvmField val ClipPath = named("clipPath")
        @JvmField val BackgroundImage = named("backgroundImage")
        @JvmField val Background = named("background")
        @JvmField val ListStyleType = named("listStyleType")
        @JvmField val ListStylePosition = named("listStylePosition")
        @JvmField val TextIndent = named("textIndent")
        @JvmField val WritingMode = named("writingMode")
        @JvmField val BorderCollapse = named("borderCollapse")
        @JvmField val Color = named("color")
        @JvmField val FontSize = named("fontSize")
        @JvmField val LineHeight = named("lineHeight")
        @JvmField val FontWeight = named("fontWeight")
        @JvmField val FontStyle = named("fontStyle")
        @JvmField val TextDecoration = named("textDecoration")
        @JvmField val TextDecorationLine = named("textDecorationLine")
        @JvmField val Opacity = named("opacity")
        @JvmField val TextAlign = named("textAlign")
        @JvmField val FontFeatureSettings = named("fontFeatureSettings")
    }
}

/**
 * Immutable property assignments. Native consumers use property identities; the read-only Map
 * view keeps the existing box/table readers and their declaration iteration order unchanged.
 */
public class NativeCssDeclarations private constructor(
    private val properties: Array<NativeCssProperty>,
    private val assigned: Array<String>,
    private val bySlot: IntArray,
    private val contentHash: Int,
    private val extended: Map<NativeCssProperty, String> = emptyMap(),
) : AbstractMap<String, String>() {
    override val size: Int get() = properties.size
    @Volatile private var beforeValue: NativeCssDeclarations? = null
    internal val affectsTypography = properties.any {
        it.name in TYPOGRAPHY_PROPERTIES
    }
    internal val affectsBox = properties.any { it.name in BOX_PROPERTIES }
    internal val relativeBox = properties.indices.any {
        properties[it].name in BOX_PROPERTIES &&
            (assigned[it].contains("em", ignoreCase = true) || assigned[it].contains('%') ||
                (properties[it].name.startsWith("margin") || properties[it].name == "padding") &&
                assigned[it].trim().split(BOX_SPACE).any { token -> token.toFloatOrNull()?.let { value -> value != 0f } == true })
    }

    internal val before: NativeCssDeclarations?
        get() {
            if (this[NativeCssProperty.BeforeContent].isNullOrEmpty()) return null
            beforeValue?.let { return it }
            return compile(buildMap {
                for (index in properties.indices) {
                    val name = properties[index].name
                    if (name.startsWith("before") && name.length > 6) {
                        put(name.substring(6).replaceFirstChar { it.lowercase() }, assigned[index])
                    }
                }
            }).also { beforeValue = it }
        }

    internal operator fun get(property: NativeCssProperty): String? {
        if (property.slot >= bySlot.size) return extended[property]
        val position = bySlot[property.slot]
        return if (position == 0) null else assigned[position - 1]
    }

    override fun get(key: String): String? = NativeCssProperty.find(key)?.let { get(it) }
    override fun containsKey(key: String): Boolean = get(key) != null
    override fun hashCode(): Int = contentHash
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeCssDeclarations) return super.equals(other)
        if (contentHash != other.contentHash || size != other.size) return false
        return properties.indices.all { assigned[it] == other[properties[it]] }
    }

    override val entries: Set<Map.Entry<String, String>>
        get() = object : AbstractSet<Map.Entry<String, String>>() {
            override val size: Int get() = properties.size
            override fun iterator(): Iterator<Map.Entry<String, String>> = object : Iterator<Map.Entry<String, String>> {
                private var index = 0
                override fun hasNext() = index < properties.size
                override fun next(): Map.Entry<String, String> {
                    if (!hasNext()) throw NoSuchElementException()
                    val current = index++
                    return java.util.AbstractMap.SimpleImmutableEntry(properties[current].name, assigned[current])
                }
            }
        }

    companion object {
        private const val DIRECT_SLOTS = 128
        private val BOX_SPACE = Regex("\\s+")
        private val TYPOGRAPHY_PROPERTIES = setOf("color", "fontSize", "lineHeight", "fontWeight",
            "fontStyle", "textDecoration", "textDecorationLine", "opacity", "verticalAlign",
            "textAlign", "fontFeatureSettings")
        private val BOX_PROPERTIES = setOf("width", "opacity", "textAlign", "verticalAlign", "wordBreak",
            "backgroundColor", "background", "border", "borderColor", "borderWidth", "borderStyle",
            "borderLeftColor", "borderRadius", "borderInlineStart", "borderLeft",
            "padding", "paddingLeft", "paddingInlineStart", "paddingRight", "paddingInlineEnd",
            "paddingTop", "paddingBlockStart", "paddingBottom", "paddingBlockEnd", "paddingBlock", "paddingInline",
            "margin", "marginLeft", "marginInlineStart", "marginRight", "marginInlineEnd",
            "marginTop", "marginBlockStart", "marginBottom", "marginBlockEnd")
        val EMPTY = NativeCssDeclarations(emptyArray(), emptyArray(), IntArray(0), 0)

        internal fun compile(styles: Map<String, String>): NativeCssDeclarations {
            if (styles is NativeCssDeclarations) return styles
            if (styles.isEmpty()) return EMPTY
            val entries = styles.entries.toList()
            val properties = Array(entries.size) { NativeCssProperty.named(entries[it].key) }
            // Property slots are stylesheet identities, so reads need neither name hashing nor
            // a binary search. Keep assignments ordered separately for shorthand precedence.
            // Arbitrary dictionary property names must not enlarge every later declaration.
            var directSize = 0
            for (property in properties) if (property.slot < DIRECT_SLOTS) directSize = maxOf(directSize, property.slot + 1)
            val bySlot = IntArray(directSize)
            var extended: MutableMap<NativeCssProperty, String>? = null
            for (index in properties.indices) {
                val property = properties[index]
                if (property.slot < directSize) bySlot[property.slot] = index + 1
                else (extended ?: HashMap<NativeCssProperty, String>().also { extended = it })[property] = entries[index].value
            }
            return NativeCssDeclarations(properties, Array(entries.size) { entries[it].value },
                bySlot, styles.hashCode(), extended ?: emptyMap())
        }
    }
}

internal class NativeCssRuleBlock(val assignments: NativeCssDeclarations, val skipMargins: Boolean = false,
    val skipPadding: Boolean = false)

/** Compiled once per stylesheet; the identity index is read-only after construction. */
internal class NativeCssRuleDeclarations(css: ParsedCss) {
    private class Rule(styles: Map<String, String>) {
        private val assignments = NativeCssDeclarations.compile(styles)
        val normal = NativeCssRuleBlock(assignments)
        val structural = if (styles.keys.any { it.startsWith("margin") }) NativeCssRuleBlock(assignments, true) else normal
        val reset = if (styles.keys.any { it.startsWith("margin") || it.startsWith("padding") })
            NativeCssRuleBlock(assignments, true, true) else normal
    }
    private val rules = IdentityHashMap<Map<String, String>, Rule>()

    init {
        fun register(styles: Map<String, String>) {
            if (!rules.containsKey(styles)) rules[styles] = Rule(styles)
        }
        css.tagStyles.values.forEach(::register)
        css.selectorStyles.values.forEach(::register)
        css.presenceRules.forEach { register(it.styles) }
        css.siblingRules.forEach { register(it.styles) }
        css.nestedTagRules.forEach { register(it.styles) }
        css.hasRules.forEach { register(it.styles) }
    }

    fun block(styles: Map<String, String>, structural: Boolean = false, reset: Boolean = false): NativeCssRuleBlock {
        val rule = checkNotNull(rules[styles]) { "Uncompiled stylesheet declaration" }
        return if (reset) rule.reset else if (structural) rule.structural else rule.normal
    }
}

/** Worker-owned style combinations; no term results, shaped text or layouts are retained. */
internal class NativeCssCascade(var collectStageTimings: Boolean = false) {
    var ancestorNanos = 0L
    private val canonical = HashMap<NativeCssDeclarations, NativeCssDeclarations>()
    private val inlineRules = HashMap<NativeCssDeclarations, NativeCssRuleBlock>()
    private val tableInheritance = IdentityHashMap<NativeCssDeclarations, NativeCssRuleBlock>()
    val root = State(null, null)

    internal class State(private val previous: State?, private val block: NativeCssRuleBlock?) {
        private var following: IdentityHashMap<NativeCssRuleBlock, State>? = null
        private var result: NativeCssDeclarations? = null

        fun append(block: NativeCssRuleBlock): State {
            if (block.assignments.isEmpty()) return this
            val transitions = following ?: IdentityHashMap<NativeCssRuleBlock, State>().also { following = it }
            return transitions[block] ?: State(this, block).also { transitions[block] = it }
        }

        fun resolve(cascade: NativeCssCascade): NativeCssDeclarations {
            result?.let { return it }
            val chain = ArrayList<NativeCssRuleBlock>()
            var current: State? = this
            while (current != null) {
                current.block?.let(chain::add)
                current = current.previous
            }
            // Preserve the existing shorthand/box-reader ordering when compiling a NEW rule
            // combination. Repeated elements follow the transitions above and allocate no map.
            var declarations: Map<String, String> = emptyMap()
            var owned: MutableMap<String, String>? = null
            fun mutable(): MutableMap<String, String> = owned ?: HashMap(declarations).also {
                owned = it
                declarations = it
            }
            for (index in chain.indices.reversed()) {
                val rule = chain[index]
                val styles = rule.assignments
                if (rule.skipMargins || rule.skipPadding) {
                    for ((key, value) in styles) {
                        if ((!rule.skipMargins || !key.startsWith("margin")) &&
                            (!rule.skipPadding || !key.startsWith("padding"))) mutable()[key] = value
                    }
                } else if (declarations.isEmpty()) {
                    declarations = styles
                } else if (declarations !== styles) {
                    mutable().putAll(styles)
                }
            }
            return cascade.intern(declarations).also { result = it }
        }
    }

    fun inline(styles: NativeCssDeclarations): NativeCssRuleBlock =
        inlineRules.getOrPut(styles) { NativeCssRuleBlock(styles) }

    fun tableRowInheritance(styles: NativeCssDeclarations): NativeCssRuleBlock =
        tableInheritance.getOrPut(styles) { inline(intern(styles.filterKeys { it in inheritedTableKeys })) }

    fun intern(styles: Map<String, String>): NativeCssDeclarations {
        val compiled = NativeCssDeclarations.compile(styles)
        return canonical[compiled] ?: compiled.also { canonical[it] = it }
    }
}
