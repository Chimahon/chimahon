package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `var()` chain whose first reference is undefined must fall through to its fallback, and that
 * fallback may itself be a `var()` chain. The WebView resolves this to the fallback's value; the
 * native renderer used to drop the whole declaration, which turned white text on a coloured
 * background into invisible white text on white.
 */
class VarChainBackgroundTest {
    private val shougakukanRule = """
        [data-sc使い分け] {
            padding: 0.125em;
            font-size: 0.8em;
            font-weight: normal;
            color: #FFF;
            background-color: var(--rgko12-light-light-brown, var(--tag-default-background-color, var(--canvas-elevated)));
        }
    """.trimIndent()

    @Test fun undefinedVariableFallsThroughToNestedFallback() {
        val resolved = resolveCssVarRefs(
            "var(--rgko12-light-light-brown, var(--tag-default-background-color, var(--canvas-elevated)))",
            BASE_CSS_VARIABLES,
        )
        // --rgko12-light-light-brown is undefined; --tag-default-background-color is defined in
        // base.css, so it is the value the browser computes.
        assertEquals("#8a8a91", resolved.trim(), "var chain did not resolve to the nested fallback")
    }

    @Test fun backgroundColourSurvivesTheWholeChain() {
        val parsed = parseDictionaryCss(shougakukanRule)
        val declarations = getCssStyles(mapOf("使い分け" to ""), parsed, "span")
        assertNotNull(declarations, "the rule produced no declarations")
        val background = declarations["backgroundColor"]
        assertNotNull(background, "background-color was dropped")
        assertEquals("#8a8a91", background)
    }

    @Test fun boxStyleKeepsTheBackground() {
        val parsed = parseDictionaryCss(shougakukanRule)
        val declarations = getCssStyles(mapOf("使い分け" to ""), parsed, "span")
        val box = parseBoxStyle(declarations, 14f)
        assertTrue(box.hasBackground, "box lost the background flag")
        assertEquals("#8a8a91", box.backgroundColor)
    }

    @Test fun undefinedVariableWithNoUsableFallbackIsDropped() {
        // CSS makes such a declaration "invalid at computed value time": the property becomes
        // unset rather than keeping the var() reference.
        val parsed = parseDictionaryCss(
            "[data-sc-x] { background-color: var(--nope-not-defined); }",
        )
        val background = getCssStyles(mapOf("x" to ""), parsed, "span")["backgroundColor"]
        assertTrue(background == null || !background.contains("var("),
            "an unresolvable var() reference leaked into the declaration: $background")
    }
}
