package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryTypographyTest {
    @Test fun annotationDefaultsResetLineHeightAndDecorationAndClampTheSize() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 19.6.sp, textDecoration = TextDecoration.Underline)
        val reading = rubyAnnotationStyle(base, base, NativeCssDeclarations.EMPTY)
        assertEquals(8.sp, reading.fontSize)
        assertEquals(TextUnit.Unspecified, reading.lineHeight)
        assertNull(reading.textDecoration)
    }

    @Test fun annotationDeclarationsKeepTheirOwnRelativeSizeAndLineHeight() {
        val base = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)
        val css = NativeCssDeclarations.compile(mapOf("fontSize" to ".6em", "lineHeight" to "1.5"))
        val inherited = applyTypography(base, css)
        val reading = rubyAnnotationStyle(base, inherited, css)
        assertEquals(12.sp, reading.fontSize)
        assertEquals(18.sp, reading.lineHeight)
    }

    @Test fun numericWeightsPreserveTheRequestedWeight() {
        val base = TextStyle()
        assertEquals(900, applyTypography(base, mapOf("fontWeight" to "900")).fontWeight?.weight)
        assertEquals(400, applyTypography(base, mapOf("fontWeight" to "400")).fontWeight?.weight)
        assertNull(applyTypography(base, mapOf("fontWeight" to "1001")).fontWeight)
    }

    @Test fun boxOnlyDeclarationsLeaveTypographyUntouched() {
        val base = TextStyle(fontFeatureSettings = "'palt' 1")
        for (css in listOf(emptyMap(), mapOf("marginTop" to "8px", "display" to "block"))) {
            val modifier = GlossaryTypography.read(css)
            assertFalse(modifier.changes)
            assertSame(base, modifier.apply(base))
        }
        assertTrue(GlossaryTypography.read(mapOf("fontWeight" to "bold")).changes)
    }

    @Test fun fontFeaturesInheritOverrideAndReset() {
        val base = TextStyle(fontFeatureSettings = "'palt' 1")
        assertEquals("'palt' 1", applyTypography(base, emptyMap()).fontFeatureSettings)
        assertEquals("'halt' 0", applyTypography(base, mapOf("fontFeatureSettings" to "'halt' 0")).fontFeatureSettings)
        assertNull(applyTypography(base, mapOf("fontFeatureSettings" to "normal")).fontFeatureSettings)
    }
}
