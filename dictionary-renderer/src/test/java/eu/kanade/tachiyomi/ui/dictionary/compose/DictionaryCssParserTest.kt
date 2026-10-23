package eu.kanade.tachiyomi.ui.dictionary.compose

import kotlin.random.Random
import java.util.BitSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DictionaryCssParserTest {
    @Test fun nestedDispatchSkipsUnrelatedValuesAndKeepsCascadeOrder() {
        val rules = (0 until 100).map { index ->
            NestedTagRule(ancestors = setOf("root"), tag = "span",
                qualifier = mapOf("content" to "kind$index"), styles = mapOf("color" to "$index"))
        } + listOf(
            NestedTagRule(tag = "span", styles = mapOf("backgroundColor" to "white")),
            NestedTagRule(ancestors = setOf("root"), tag = "span",
                qualifier = mapOf("content" to "kind17"), styles = mapOf("color" to "last")),
        )
        val css = ParsedCss(nestedTagRules = rules)
        val node = StructuredNode.Element(StructuredTag.Span, attributes = StructuredAttributes(data = mapOf("content" to "kind17")))
        val scratch = BitSet()
        assertEquals(3, css.nestedCandidates("span", node.attributes.data, node.attributes.cssKeys.values, scratch).cardinality())
        val context = NativeCssContext(css)
        context.push(setOf("root"), "div")
        assertEquals(mapOf("backgroundColor" to "white", "color" to "last"), context.declarations(node))
        context.pop()
        assertEquals(mapOf("backgroundColor" to "white"), context.declarations(node))
        assertEquals(0, css.nestedCandidates("td", node.attributes.data, node.attributes.cssKeys.values, scratch).cardinality())
    }

    @Test fun nestedDispatchDistinguishesPresenceValueAndClassAliases() {
        val css = ParsedCss(nestedTagRules = listOf(
            NestedTagRule(target = CssCompound(presences = setOf("mark")), styles = mapOf("presence" to "yes")),
            NestedTagRule(target = CssCompound(values = setOf("mark")), styles = mapOf("value" to "yes")),
            NestedTagRule(qualifier = mapOf("class" to "alpha"), styles = mapOf("class" to "yes")),
            NestedTagRule(target = CssCompound(values = setOf(".alpha", "alpha")), styles = mapOf("aliases" to "yes")),
            NestedTagRule(qualifier = mapOf("exact" to "mark"), styles = mapOf("exact" to "yes")),
            NestedTagRule(qualifier = mapOf("class" to ""), styles = mapOf("emptyClass" to "yes")),
        ))
        val context = NativeCssContext(css)
        fun styles(data: Map<String, String>) = context.declarations(
            StructuredNode.Element(StructuredTag.Span, attributes = StructuredAttributes(data = data)))
        assertEquals(mapOf("presence" to "yes"), styles(mapOf("mark" to "")))
        assertEquals(mapOf("value" to "yes"), styles(mapOf("other" to "mark")))
        assertEquals(mapOf("value" to "yes", "exact" to "yes"), styles(mapOf("exact" to "mark")))
        assertEquals(mapOf("class" to "yes", "aliases" to "yes"), styles(mapOf("class" to "beta\talpha")))
        assertEquals(mapOf("emptyClass" to "yes"), styles(mapOf("class" to "")))
        assertEquals(emptyMap<String, String>(), styles(emptyMap()))
    }

    @Test fun parsedAttributeKeysPreserveDuplicateCascadeOrder() {
        val attributes = StructuredAttributes(data = linkedMapOf(
            "first" to "meaning", "class" to "alpha\tbeta alpha", "last" to "meaning", "blank" to " "))
        val values = listOf("meaning", ".alpha", "alpha", ".beta", "beta", ".alpha", "alpha", "meaning")
        assertEquals(values, attributes.cssKeys.values)
        assertEquals((listOf("\u0000key:first", "\u0000key:class", "\u0000key:last", "\u0000key:blank") + values).toSet(),
            attributes.cssKeys.ancestors)
        val copy = attributes.copy(data = attributes.data + ("class" to "gamma"))
        assertEquals(listOf("meaning", ".gamma", "gamma", "meaning"), copy.cssKeys.values)
        assertEquals(values, attributes.cssKeys.values)
        assertSame(StructuredAttributes().cssKeys, StructuredAttributes().cssKeys)
    }

    @Test fun fusedNativeCascadeMatchesTheSeparateRuleFamilies() {
        val css = parseDictionaryCss("""
            div { margin: 8px; color: gray; }
            [data-sc-item] { margin: 9px; color: black; }
            span[data-sc-item] { color: red; font-weight: bold; }
            [data-sc-parent] span { color: green; }
            [data-sc-prev] + [data-sc-item] { background-color: yellow; }
            [data-sc-prev] ~ [data-sc-item] { text-decoration: underline; }
            [data-sc-item]:has(ruby) { color: purple; }
        """.trimIndent())
        val random = Random(31)
        repeat(200) {
            val context = NativeCssContext(css)
            val parent = if (random.nextBoolean()) mapOf("parent" to "") else emptyMap()
            context.push(dataValueKeys(parent), "div")
            val data = if (random.nextBoolean()) mapOf("item" to "") else emptyMap()
            val inline = if (random.nextBoolean()) mapOf("color" to "gold", "marginTop" to "2px") else emptyMap()
            val node = StructuredNode.Element(if (random.nextBoolean()) StructuredTag.Div else StructuredTag.Span,
                if (random.nextBoolean()) listOf(StructuredNode.Element(StructuredTag.Ruby)) else emptyList(),
                StructuredAttributes(data = data, style = inline))
            val preceding = if (random.nextBoolean()) listOf(mapOf("prev" to "")) else emptyList()
            val expected = mergeCssStyles(mergeCssStyles(
                getCssStyles(data, css, node.tag.tagName, structuralMargins = node.tag == StructuredTag.Div),
                getSiblingStyles(data, css, preceding), context.nestedStyles(node.tag.tagName, data),
                context.hasStyles(node)), inline)
            assertEquals(expected, context.declarations(node, preceding))
            context.pop()
        }
    }

    @Test fun fusedDeclarationsAreIndependentAcrossElementsAndContexts() {
        val css = parseDictionaryCss("[data-sc-parent] span { color: red; } span { background-color: white; }")
        val context = NativeCssContext(css)
        val node = StructuredNode.Element(StructuredTag.Span)
        context.push(dataValueKeys(mapOf("parent" to "")), "div")
        val scoped = context.declarations(node)
        context.pop()
        val outside = context.declarations(node)
        val inline = context.declarations(node.copy(attributes = StructuredAttributes(style = mapOf("backgroundColor" to "black"))))
        assertEquals("red", scoped["color"])
        assertEquals(null, outside["color"])
        assertEquals("red", scoped["color"])
        assertEquals("white", outside["backgroundColor"])
        assertEquals("black", inline["backgroundColor"])
    }

    @Test fun moreSpecificTableCornerBeatsLaterFirstColumnRule() {
        val css = parseDictionaryCss("""
            table[data-sc-header] tr:first-child td:first-child { background-color: gray; }
            table[data-sc-header] td:first-child { background-color: brown; color: black; }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(dataValueKeys(mapOf("header" to "")), "table")
        context.push(emptySet(), "tr", 1, false)
        val corner = context.nestedStyles("td", emptyMap(), 1, false)
        assertEquals("gray", corner["backgroundColor"])
        assertEquals("black", corner["color"])
        assertEquals(corner, getNestedStyles("td", emptyMap(), context.ancestors(), css, 1, false))
        context.pop()
        context.push(emptySet(), "tr", 2, false)
        assertEquals("brown", context.nestedStyles("td", emptyMap(), 1, false)["backgroundColor"])
    }

    @Test fun nestedSpecificityKeepsSourceOrderTiesAndCountsClassAboveTypes() {
        val css = parseDictionaryCss("""
            [data-sc-mark] span { color: red; background-color: white; }
            div section span { color: green; }
            [data-sc-mark] span { background-color: gray; }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(dataValueKeys(mapOf("mark" to "")), "div")
        context.push(emptySet(), "section")
        val result = context.nestedStyles("span", emptyMap())
        assertEquals("red", result["color"])
        assertEquals("gray", result["backgroundColor"])
        assertEquals(result, getNestedStyles("span", emptyMap(), context.ancestors(), css))
    }

    @Test fun shippingDivMarginsOverrideWeakRulesWithoutDroppingOtherProperties() {
        val css = parseDictionaryCss("""
            div { margin: 8px; }
            [data-sc-section] { margin-block-start: 0.5em; color: red; }
            div[data-sc-section][data-sc-special] { margin-block-start: 0; }
        """.trimIndent())
        val section = getCssStyles(mapOf("section" to ""), css, "div", structuralMargins = true)
        assertEquals("red", section["color"])
        assertEquals(false, section.keys.any { it.startsWith("margin") })
        val special = getCssStyles(mapOf("section" to "", "special" to ""), css, "div", structuralMargins = true)
        assertEquals("0", special["marginBlockStart"])
        assertEquals("0.5em", getCssStyles(mapOf("section" to ""), css, "div")["marginBlockStart"])
    }

    @Test fun nestedGeneratedContentDoesNotLeakOutOfItsOwningElement() {
        val css = parseDictionaryCss("""
            div[data-sc-content="forms"] { & span { &::before { content: "marker"; } } }
        """.trimIndent())
        assertEquals(null, getCssStyles(mapOf("content" to "forms"), css, "div")["beforeContent"])
        val context = NativeCssContext(css)
        context.push(setOf("forms"), "div")
        assertEquals("marker", context.nestedStyles("span", emptyMap())["beforeContent"])
    }

    @Test fun nestedBeforeContentBelongsToTheSelectedChild() {
        val glyph = "\u25c7"
        val css = parseDictionaryCss("""
            td[data-sc-class="form-valid"] > span {
                color: white;
                &::before { content: "$glyph"; }
            }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(dataValueKeys(mapOf("class" to "form-valid")), "td")
        assertEquals(glyph, context.nestedStyles("span", emptyMap())["beforeContent"])
        assertEquals(null, getCssStyles(mapOf("class" to "form-valid"), css, "td")["beforeContent"])
    }

    @Test fun generatedImageContainerReceivesScopedSizing() {
        val css = parseDictionaryCss("""
            [data-sc-img][data-sc-gaiji] .gloss-image-container { width: 10em !important; }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(dataValueKeys(mapOf("img" to "", "gaiji" to "")), "span")
        assertEquals("10em", context.nestedStyles("span", mapOf("class" to "gloss-image-container"))["width"])
    }

    @Test fun plainTypeRulesApplyWithoutAttributesAndLoseToQualifiedRules() {
        val css = parseDictionaryCss("""
            rt[data-sc-em] { font-weight: bold; }
            rt { font-size: 0.4em; font-weight: bold; }
            rt, rp { font-size: 0.5em; font-weight: normal; }
        """.trimIndent())
        val normal = mapOf("fontSize" to "0.5em", "fontWeight" to "normal")
        assertEquals(normal, getCssStyles(emptyMap(), css, "rt"))
        assertEquals(normal, getCssStyles(mapOf("rt" to ""), css, "rt"))
        assertEquals(normal, getCssStyles(emptyMap(), css, "rp"))
        assertEquals("bold", getCssStyles(mapOf("em" to ""), css, "rt")["fontWeight"])
        assertEquals(emptyMap<String, String>(), getCssStyles(emptyMap(), css, "span"))
        assertEquals(normal, matchFlatStyles(emptyMap(), css, tag = "rt").declarations)
        assertEquals(emptyMap<String, String>(), matchFlatStyles(emptyMap(), css, tag = "rt").strong)
    }

    @Test fun flatCascadeTiersKeepQualifiedStylesButExcludeBareRules() {
        val css = parseDictionaryCss("""
            [data-sc-content="sense"] { color: red; margin-top: 3px; }
            li[data-sc-content="sense"] { color: blue; font-weight: bold; }
            [data-sc-flag] { color: green; }
        """.trimIndent())
        val data = mapOf("content" to "sense", "flag" to "")
        val all = mapOf("color" to "green", "marginTop" to "3px", "fontWeight" to "bold")
        val strong = mapOf("color" to "blue", "fontWeight" to "bold")
        assertEquals(all, getCssStyles(data, css, "li"))
        assertEquals(strong, getStrongFlatStyles(data, css, "li"))
        assertEquals(all, matchFlatStyles(data, css, tag = "li").declarations)
        assertEquals(strong, matchFlatStyles(data, css, tag = "li").strong)
        assertEquals(emptyMap<String, String>(), matchFlatStyles(data, css, false, "li").strong)
        assertEquals(emptyMap<String, String>(), getStrongFlatStyles(data, css, "div"))
        assertEquals(mapOf("color" to "green", "marginTop" to "3px"), getCssStyles(data, css, "div"))
    }

    @Test fun flatTagQualifiedValuesDoNotStyleAnotherElementTag() {
        val css = parseDictionaryCss("""li[data-sc-content="sense"] { padding-left: 0.25em; }""")
        assertEquals(null, getCssStyles(mapOf("content" to "sense"), css, "div")["paddingLeft"])
        assertEquals("0.25em", getCssStyles(mapOf("content" to "sense"), css, "li")["paddingLeft"])
        assertEquals(getCssStyles(mapOf("content" to "sense"), css, "li"),
            matchFlatStyles(mapOf("content" to "sense"), css, tag = "li").declarations)
    }

    @Test fun nestingKeepsTagAndAttributesOnTheSameAncestor() {
        val css = parseDictionaryCss("""
            li[data-sc-content="sense"] { & ul[data-sc-content="glossary"] { list-style-type: none; } }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(setOf("sense"), "div")
        context.push(emptySet(), "li")
        assertEquals(null, context.nestedStyles("ul", mapOf("content" to "glossary"))["listStyleType"])
        context.pop()
        context.pop()
        context.push(setOf("sense"), "li")
        assertEquals("none", context.nestedStyles("ul", mapOf("content" to "glossary"))["listStyleType"])
        assertEquals(getNestedStyles("ul", mapOf("content" to "glossary"), context.ancestors(), css),
            context.nestedStyles("ul", mapOf("content" to "glossary")))
    }

    @Test fun nestingSelectorListsAreAlternativesAndDeeperScopesKeepTheirOrder() {
        val css = parseDictionaryCss("""
            div[data-sc-content="one"], li[data-sc-content="two"] {
                & td { & > span { color: red; } }
            }
        """.trimIndent())
        val context = NativeCssContext(css)
        context.push(setOf("one"), "div")
        context.push(emptySet(), "td")
        assertEquals("red", context.nestedStyles("span", emptyMap())["color"])
        context.push(emptySet(), "div")
        assertEquals(null, context.nestedStyles("span", emptyMap())["color"])
        context.pop()
        context.pop()
        context.pop()
        context.push(emptySet(), "td")
        context.push(setOf("one"), "div")
        assertEquals(null, context.nestedStyles("span", emptyMap())["color"])
    }

    private val sheet = ParsedCss(nestedTagRules = listOf(
        NestedTagRule(setOf("outer"), tag = "span", styles = mapOf("color" to "red")),
        NestedTagRule(setOf("outer"), parentTag = "div", direct = true, tag = "span", styles = mapOf("color" to "blue")),
        NestedTagRule(setOf("inner"), parentTag = "section", tag = "span", styles = mapOf("fontWeight" to "bold")),
        NestedTagRule(setOf(".marked"), tag = "", qualifier = mapOf("class" to "leaf"), styles = mapOf("color" to "green")),
        NestedTagRule(setOf("outer"), tag = "td", positional = 0, styles = mapOf("padding" to "1px")),
        NestedTagRule(setOf("outer"), tag = "td", positional = -1, styles = mapOf("padding" to "2px")),
        NestedTagRule(setOf("outer"), tag = "td", positional = 2, styles = mapOf("color" to "black")),
    ))

    private fun compare(context: NativeCssContext, ancestors: List<CssAncestor>) {
        for (tag in listOf("span", "td", "div", "")) {
            for (data in listOf(emptyMap(), mapOf("class" to "other leaf"), mapOf("class" to "leaflet"))) {
                for (index in listOf(null, 1, 2, 3)) {
                    for (last in listOf(false, true)) {
                        assertEquals(getNestedStyles(tag, data, ancestors, sheet, index, last),
                            context.nestedStyles(tag, data, index, last), "tag=$tag data=$data ancestors=$ancestors index=$index last=$last")
                    }
                }
            }
        }
    }

    @Test fun directAndDescendantMatchingPreserveSourceOrder() {
        val context = NativeCssContext(sheet)
        val root = CssAncestor(setOf("outer", ".marked"), "div")
        context.push(root.values, root.tag)
        compare(context, listOf(root))
        assertEquals("blue", context.nestedStyles("span", emptyMap())["color"])
        val child = CssAncestor(setOf("inner"), "section")
        context.push(child.values, child.tag)
        compare(context, listOf(root, child))
        assertEquals("red", context.nestedStyles("span", emptyMap())["color"])
        assertEquals("green", context.nestedStyles("span", mapOf("class" to "leaf"))["color"])
    }

    @Test fun repeatedKeysSurviveChildExit() {
        val context = NativeCssContext(sheet)
        val root = CssAncestor(setOf("outer"), "div")
        context.push(root.values, root.tag)
        context.push(root.values, root.tag)
        context.pop()
        compare(context, listOf(root))
        context.pop()
        compare(context, emptyList())
    }

    @Test fun emptyAndTagOnlyAncestorsKeepExistingSemantics() {
        val context = NativeCssContext(sheet)
        compare(context, emptyList())
        context.push(emptySet(), "div")
        compare(context, listOf(CssAncestor(emptySet(), "div")))
        val inactive = NativeCssContext(ParsedCss.EMPTY)
        inactive.push(setOf("outer"), "div")
        inactive.pop()
        assertEquals(emptyMap<String, String>(), inactive.nestedStyles("span", emptyMap()))
    }

    @Test fun exceptionsRestoreTheParentContext() {
        val context = NativeCssContext(sheet)
        val root = CssAncestor(setOf("outer"), "div")
        context.push(root.values, root.tag)
        assertThrows(IllegalStateException::class.java) {
            context.push(setOf("inner", ".marked"), "section")
            try { error("cancel traversal") } finally { context.pop() }
        }
        compare(context, listOf(root))
    }

    @Test fun ancestorSnapshotsDoNotFollowLaterPushesAndPops() {
        val context = NativeCssContext(sheet)
        val root = CssAncestor(setOf("outer"), "div")
        context.push(root.values, root.tag)
        val snapshot = context.ancestors()
        context.push(setOf("inner"), "section")
        context.pop()
        context.pop()
        assertEquals(listOf(root), snapshot)
    }

    @Test fun bareNamesValuesAndClassTokensRemainDistinct() {
        val css = ParsedCss(nestedTagRules = listOf(
            NestedTagRule(dataValueKeys(mapOf("meaning" to "")), tag = "span", styles = mapOf("color" to "red")),
            NestedTagRule(setOf(".marked"), tag = "span", styles = mapOf("fontWeight" to "bold")),
        ))
        for (data in listOf(mapOf("other" to "meaning"), mapOf("meaning" to "", "class" to "x marked"))) {
            val context = NativeCssContext(css)
            val root = CssAncestor(dataValueKeys(data), "div")
            context.push(root.values, root.tag)
            assertEquals(getNestedStyles("span", emptyMap(), listOf(root), css), context.nestedStyles("span", emptyMap()))
        }
    }

    @Test fun randomizedTraversalMatchesTheIndependentListMatcher() {
        val random = Random(20261019)
        val context = NativeCssContext(sheet)
        val ancestors = ArrayList<CssAncestor>()
        repeat(500) {
            if (ancestors.isNotEmpty() && (ancestors.size == 12 || random.nextBoolean())) {
                context.pop()
                ancestors.removeAt(ancestors.lastIndex)
            } else {
                val values = listOf("outer", "inner", ".marked", "other").filter { random.nextBoolean() }.toSet()
                val frame = CssAncestor(values, listOf("div", "section", "span")[random.nextInt(3)])
                ancestors.add(frame)
                context.push(frame.values, frame.tag)
            }
            compare(context, ancestors)
        }
        while (ancestors.isNotEmpty()) { context.pop(); ancestors.removeAt(ancestors.lastIndex) }
        compare(context, emptyList())
    }

    @Test fun declarationsOnlyFlatMatchDoesNotChangeEitherCascadeTier() {
        val css = ParsedCss(selectorStyles = mapOf("weak" to mapOf("color" to "red"), "strong" to mapOf("fontWeight" to "bold")),
            weakValueKeys = setOf("weak"), presenceRules = listOf(CssPresenceRule(setOf("flag"), mapOf("color" to "blue"), false, true)))
        for (data in listOf(emptyMap(), mapOf("content" to "weak"), mapOf("content" to "weak", "class" to "strong", "flag" to ""))) {
            assertEquals(matchFlatStyles(data, css).declarations, getCssStyles(data, css))
            assertEquals(matchFlatStyles(data, css, false).declarations, getCssStyles(data, css))
        }
    }
}
