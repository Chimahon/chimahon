package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import eu.kanade.presentation.theme.colorscheme.CustomColorScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryColorSchemeTest {
    private fun roles(scheme: ColorScheme) = ColorScheme::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType && it.name.startsWith("get") }
        .associate { it.name to it.invoke(scheme) }

    @Test fun paletteMatchesEveryOriginalColorRoleInAllModes() {
        for (seed in listOf(0xff4285f4.toInt(), 0xffef3340.toInt())) {
            val reference = CustomColorScheme(Color(seed), PaletteStyle.TonalSpot)
            assertTrue(roles(reference.lightScheme).size >= 29)
            for (dark in listOf(false, true)) for (amoled in listOf(false, true)) {
                assertEquals(roles(reference.getColorScheme(dark, amoled, false)),
                    roles(getDictionaryColorScheme(dark, amoled, seed)))
            }
        }
    }

    @Test fun paletteIsSharedAcrossPopupsAndInvalidatedByThemeChanges() {
        val seed = 0xff64b539.toInt()
        val first = getDictionaryColorScheme(false, false, seed)
        assertSame(first, getDictionaryColorScheme(false, false, seed))
        val dark = getDictionaryColorScheme(true, false, seed)
        assertNotSame(first, dark)
        val amoled = getDictionaryColorScheme(true, true, seed)
        assertEquals(Color.Black, amoled.surface)
        assertSame(amoled, getDictionaryColorScheme(true, true, seed))
        assertNotSame(amoled, getDictionaryColorScheme(true, true, 0xff6750a4.toInt()))
    }
}
