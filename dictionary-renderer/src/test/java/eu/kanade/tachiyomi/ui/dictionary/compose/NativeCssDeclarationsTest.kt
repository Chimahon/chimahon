package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeCssDeclarationsTest {
    @Test fun slotReadsPreserveOrderAndHandleLaterProperties() {
        val early = NativeCssProperty.named("slot-test-early")
        val middle = NativeCssProperty.named("slot-test-middle")
        val last = NativeCssProperty.named("slot-test-last")
        val source = linkedMapOf(last.name to "last", early.name to "first")
        val declarations = NativeCssDeclarations.compile(source)
        assertEquals("first", declarations[early])
        assertEquals("last", declarations[last])
        assertNull(declarations[middle])
        assertNull(declarations[NativeCssProperty.named("slot-test-later")])
        assertNull(NativeCssDeclarations.EMPTY[early])
        assertEquals(source.entries.toList(), declarations.entries.toList())
        assertEquals(source.hashCode(), declarations.hashCode())
        assertEquals(declarations, NativeCssDeclarations.compile(source.entries.reversed().associate { it.toPair() }))
        repeat(256) { NativeCssProperty.named("unrelated-slot-$it") }
        val distant = NativeCssProperty.named("distant-slot")
        val sparse = NativeCssDeclarations.compile(linkedMapOf(distant.name to "distant", early.name to "early"))
        assertEquals("distant", sparse[distant])
        assertEquals("early", sparse[early])
        assertNull(sparse[middle])
    }

    @Test fun declarationDomainsKeepFontRelativeBoxesSeparate() {
        val text = NativeCssDeclarations.compile(mapOf("fontSize" to "2em", "color" to "red"))
        assertTrue(text.affectsTypography)
        assertFalse(text.affectsBox)
        val fixed = NativeCssDeclarations.compile(mapOf("padding" to "2px 4px", "border" to "1px solid red"))
        assertFalse(fixed.affectsTypography)
        assertTrue(fixed.affectsBox)
        assertFalse(fixed.relativeBox)
        assertEquals(parseBoxStyle(fixed, 8f), parseBoxStyle(fixed, 32f))
        for (unit in listOf("1em", "2REM", "50%", "0.3", "-1")) {
            val relative = NativeCssDeclarations.compile(mapOf("marginTop" to unit))
            assertTrue(relative.affectsBox)
            assertTrue(relative.relativeBox)
        }
        assertFalse(NativeCssDeclarations.EMPTY.affectsTypography)
        assertFalse(NativeCssDeclarations.EMPTY.affectsBox)
        for (name in listOf("padding", "margin")) {
            assertTrue(NativeCssDeclarations.compile(mapOf(name to "0 .3")).relativeBox)
            assertFalse(NativeCssDeclarations.compile(mapOf(name to "0 0")).relativeBox)
        }
    }
}
