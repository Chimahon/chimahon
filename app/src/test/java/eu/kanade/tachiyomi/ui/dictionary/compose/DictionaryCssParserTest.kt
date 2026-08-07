package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryCssParserTest {

    @Test
    fun `parses box selectors from Jitendex style rules`() {
        val css = """
            div[data-sc-class="extra-box"] {
                border-radius: 0.4rem;
                border-style: none none none solid;
                border-width: calc(3em / 14);
                margin-bottom: 0.5rem;
                margin-top: 0.5rem;
                padding: 0.5rem;
            }
            span[data-sc-class="tag"] {
                border-radius: 0.3em;
                font-size: 0.8em;
                font-weight: bold;
            }
        """.trimIndent()

        val parsed = parseDictionaryCss(css)

        assertTrue(parsed.boxSelectors.contains("extra-box"))
        assertTrue(parsed.boxSelectors.contains("tag"))
        assertEquals("0.4rem", parsed.selectorStyles["extra-box"]?.get("borderRadius"))
        assertEquals("0.8em", parsed.selectorStyles["tag"]?.get("fontSize"))
    }

    @Test
    fun `combines multiple data selectors for one element`() {
        val css = """
            span[data-sc-content="part-of-speech-info"] {
                background-color: #565656;
                color: white;
            }
            span[data-sc-class="tag"] {
                font-size: 0.8em;
            }
        """.trimIndent()

        val parsed = parseDictionaryCss(css)

        val merged = getCssStyles(
            mapOf("content" to "part-of-speech-info", "class" to "tag"),
            parsed,
        )
        assertEquals("#565656", merged["backgroundColor"])
        assertEquals("white", merged["color"])
        assertEquals("0.8em", merged["fontSize"])
    }

    @Test
    fun `handles nested ampersand rules without truncating parent`() {
        val css = """
            li[data-sc-content="sense"] {
                padding-left: 0.25em;
                & ul[data-sc-content="glossary"] {
                    list-style-type: none;
                }
            }
        """.trimIndent()

        val parsed = parseDictionaryCss(css)

        assertEquals(
            "0.25em",
            parsed.selectorStyles["sense"]?.get("paddingLeft"),
            "outer rule declarations must survive nested & blocks",
        )
    }

    @Test
    fun `strips comments and ignores unsupported selectors`() {
        val css = """
            /* a comment */
            unmatched-selector { color: red; }
            span[data-sc-content="misc-info"] {
                background-color: brown; /* inline comment */
                color: white;
            }
        """.trimIndent()

        val parsed = parseDictionaryCss(css)

        assertTrue(parsed.selectorStyles.containsKey("misc-info"))
        assertEquals("brown", parsed.selectorStyles["misc-info"]?.get("backgroundColor"))
    }

    @Test
    fun `box style parses rem units and dimensions`() {
        val box = parseBoxStyle(
            mapOf(
                "backgroundColor" to "color-mix(in srgb, green 5%, transparent)",
                "border" to "1px solid green",
                "borderRadius" to "0.5rem",
                "padding" to "0.5rem",
                "marginTop" to "0.5rem",
            ),
            baseFontSizeSp = 16f,
        )

        assertTrue(box.hasBackground)
        assertTrue(box.hasBorder)
        assertEquals(8f, box.borderRadius)
        assertEquals(8f, box.paddingStart)
        assertEquals(8f, box.marginTop)
        assertTrue(box.hasMargin)
        assertEquals("green", box.backgroundColor)
    }

    @Test
    fun `color-mix background and var border are extracted`() {
        val box = parseBoxStyle(
            mapOf(
                "backgroundColor" to "color-mix(in srgb, #1A73E8 5%, transparent)",
                "borderColor" to "var(--text-color, var(--fg, #333))",
            ),
            baseFontSizeSp = 16f,
        )
        assertTrue(box.hasBackground)
        assertEquals("#1A73E8", box.backgroundColor)
        assertTrue(box.hasBorder, "border-color presence renders as a coloured accent")
        assertTrue(box.leftAccent)
        assertEquals("#333", box.borderColor)
    }

    @Test
    fun `em and calc borders are recognised without crashing`() {
        val box = parseBoxStyle(
            mapOf(
                "borderStyle" to "none none none solid",
                "borderWidth" to "calc(3em / var(--font-size-no-units, 14))",
                "borderRadius" to "0.4rem",
            ),
            baseFontSizeSp = 16f,
        )
        assertTrue(box.hasBorder)
        assertTrue(box.leftAccent)
        assertEquals(6.4f, box.borderRadius)
    }

    @Test
    fun `full solid border style produces a frame`() {
        val box = parseBoxStyle(
            mapOf(
                "borderStyle" to "solid",
                "borderWidth" to "1px",
            ),
            baseFontSizeSp = 16f,
        )
        assertTrue(box.hasBorder)
        assertFalse(box.leftAccent)
    }

    @Test
    fun `captures before content markers from nested pseudo rules`() {
        val parsed = parseDictionaryCss(
            """
            td[data-sc-class="form-pri"] > span {
                color: white;
                background: radial-gradient(green 50%, white 100%);
                &::before {
                    content: "△";
                }
            }
            """.trimIndent(),
        )
        assertEquals("△", parsed.selectorStyles["form-pri"]?.get("beforeContent"))
    }

    @Test
    fun `empty and blank css produce empty parsed result`() {
        assertEquals(ParsedCss.EMPTY, parseDictionaryCss(null))
        assertEquals(ParsedCss.EMPTY, parseDictionaryCss("   "))
    }

    @Test
    fun `numeric margin styles are treated as em units`() {
        // KO-EN KRDICT ships `style: {"marginRight": 0.25}` — the reference renderer treats
        // numeric margin values as em (they scale with the base font size).
        val box = parseBoxStyle(
            mapOf("marginRight" to "0.25"),
            baseFontSizeSp = 16f,
        )
        assertEquals(4f, box.marginEnd)
        assertEquals(null, box.marginStart)
    }

    @Test
    fun `parses oxford style display none and prefix selectors`() {
        val css = """
            span[data-sc-content="sn"] { display: none; }
            span[data-sc-content="ps"] { display: none; }
            details[data-sc-content^="details-entry"] { padding: 0 1em; }
            summary[data-sc-content="summary-entry"] {
                user-select: none;
                width: max-content;
                color: var(--text-color-light4);
            }
            span[data-sc-content="exg x_xd2 hasSn"] {
                display: block;
                padding: 0.25em;
                padding-left: 0.5em;
                border-style: none none none solid;
                border-radius: 5px;
                border-color: var(--shadow-color-light);
                margin: 0.5em;
            }
        """.trimIndent()

        val parsed = parseDictionaryCss(css)

        // Prefix selector `^="details-entry"` keys on the literal value, matching the node's data.
        assertEquals("none", parsed.selectorStyles["sn"]?.get("display"))
        assertEquals("none", parsed.selectorStyles["ps"]?.get("display"))
        assertEquals("0 1em", parsed.selectorStyles["details-entry"]?.get("padding"))
        assertEquals("none", parsed.selectorStyles["summary-entry"]?.get("userSelect"))

        // display:none must NOT be a box selector (it hides, doesn't box).
        assertFalse(parsed.boxSelectors.contains("sn"))
        assertTrue(parsed.boxSelectors.contains("details-entry"))
        assertTrue(parsed.boxSelectors.contains("exg x_xd2 hasSn"))
    }

    @Test
    fun `details box padding parses to dp`() {
        val box = parseBoxStyle(
            mapOf("padding" to "0 1em"),
            baseFontSizeSp = 16f,
        )
        assertEquals(0f, box.paddingTop)
        assertEquals(16f, box.paddingStart)
        assertEquals(16f, box.paddingEnd)
        assertEquals(0f, box.paddingBottom)
    }

    @Test
    fun `margin shorthand expands all four sides`() {
        val box = parseBoxStyle(
            mapOf("margin" to "0.5em 1em"),
            baseFontSizeSp = 16f,
        )
        assertEquals(8f, box.marginTop)
        assertEquals(8f, box.marginBottom)
        assertEquals(16f, box.marginStart)
        assertEquals(16f, box.marginEnd)
    }
}