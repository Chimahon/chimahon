package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Immutable popup facts shared by preparation and mounting, never mutable worker resources. */
public class NativeGlossaryLayoutContext(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val secondary: Color,
    val border: Color,
    val viewportWidth: Int,
) {
    init { require(viewportWidth >= 0 && viewportWidth != Constraints.Infinity) }

    private val singleWidth = with(density) {
        (viewportWidth - 4.dp.roundToPx() - 12.dp.roundToPx() - 12.dp.roundToPx()).coerceAtLeast(1)
    }
    private val multipleWidth = with(density) { (singleWidth - 16.dp.roundToPx()).coerceAtLeast(1) }

    fun glossaryWidth(multiple: Boolean): Int = if (multiple) multipleWidth else singleWidth

    fun createResources(): NativePreparationStats = NativePreparationStats()

}
