package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class NativeGlossaryLayoutContextTest {
    private val resolver = Proxy.newProxyInstance(FontFamily.Resolver::class.java.classLoader,
        arrayOf(FontFamily.Resolver::class.java)) { _, _, _ -> error("Geometry must not resolve fonts") } as FontFamily.Resolver

    private fun context(width: Int, density: Density = Density(1f)) = NativeGlossaryLayoutContext(
        TextStyle.Default, density, LayoutDirection.Ltr, resolver, Color.Gray, Color.Black, width)

    @Test fun assignedWidthsMatchActualRoundedPopupInsets() {
        for (scale in listOf(1f, 1.25f, 2.625f, 2.75f, 3.5f)) {
            val density = Density(scale, 1.4f)
            for (width in listOf(0, 17, 825, 1080)) {
                val context = context(width, density)
                for (multiple in listOf(false, true)) {
                    val expected = with(density) {
                        (width - 4.dp.roundToPx() - 12.dp.roundToPx() - 12.dp.roundToPx() -
                            if (multiple) 16.dp.roundToPx() else 0).coerceAtLeast(1)
                    }
                    assertEquals(expected, context.glossaryWidth(multiple))
                }
            }
        }
    }

    @Test fun sharedFactsDoNotShareMutableWorkerResources() {
        val context = context(825)
        val first = context.createResources()
        val second = context.createResources()
        assertNotSame(first, second)
        assertSame(context.density, first.density)
        assertSame(context.density, second.density)
        assertEquals(context.direction, first.direction)
        assertEquals(context.direction, second.direction)
    }

    @Test fun invalidViewportsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { context(-1) }
        assertThrows(IllegalArgumentException::class.java) { context(Constraints.Infinity) }
    }
}
