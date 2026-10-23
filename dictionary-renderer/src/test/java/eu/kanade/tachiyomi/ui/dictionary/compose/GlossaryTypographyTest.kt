package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.text.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryTypographyTest {
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
