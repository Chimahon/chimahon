package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Immutable popup facts shared by preparation and mounting, never mutable worker resources. */
public fun nativeLanguageTag(code: String): String = when {
    code.startsWith("zh") -> if (code.contains("TW") || code.contains("HK") ||
        code.contains("MO") || code.contains("Hant")) "zh-Hant" else "zh-Hans"
    code.startsWith("ko") -> "ko"
    else -> "ja"
}

public class NativeGlossaryLayoutContext(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val secondary: Color,
    val border: Color,
    val viewportWidth: Int,
    val fontFiles: List<Pair<String, Int>> = emptyList(),
    val language: String = "ja",
    /** The imported dictionary font, so Compose-drawn entry text uses it like the WebView. */
    val fontFamily: FontFamily? = null,
) {
    init { require(viewportWidth >= 0 && viewportWidth != Constraints.Infinity) }

    private val singleWidth = with(density) {
        (viewportWidth - 4.dp.roundToPx() - 12.dp.roundToPx() - 12.dp.roundToPx()).coerceAtLeast(1)
    }
    private val multipleWidth = with(density) { (singleWidth - 16.dp.roundToPx()).coerceAtLeast(1) }

    fun glossaryWidth(multiple: Boolean): Int = if (multiple) multipleWidth else singleWidth

    fun createResources(): NativePreparationStats = NativePreparationStats()

}
