package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

public data class TypographyLength(val value: Float, val relative: Boolean) {
    fun resolve(base: Float): TextUnit = (if (relative) value * base else value).sp
}

/** A CSS declaration becomes a reusable modifier; applying inheritance does not parse strings. */
public class GlossaryTypography private constructor(
    private val color: Color?,
    private val fontSize: TypographyLength?,
    private val lineHeight: TypographyLength?,
    private val lineHeightDeclared: Boolean,
    private val weight: FontWeight?,
    private val italic: Boolean,
    private val underline: Boolean,
    private val opacity: Float?,
    private val verticalShift: Int,
    private val align: TextAlign?,
    private val fontFeatures: String?,
) {
    internal val changes = color != null || fontSize != null || lineHeight != null || weight != null || italic ||
        underline || opacity != null || verticalShift != 0 || align != null || fontFeatures != null

    fun apply(base: TextStyle, density: Float = 1f): TextStyle {
        if (!changes) return base
        val currentSp = base.fontSize.value.takeIf { it > 0f } ?: 16f
        val size = fontSize?.resolve(currentSp) ?: base.fontSize
        var leading = base.lineHeight
        if (fontSize != null && !lineHeightDeclared && base.fontSize.isSp && leading.isSp &&
            base.fontSize.value > 0f && size.isSp && size.value > 0f) {
            leading *= size.value / base.fontSize.value
        }
        if (lineHeight != null) leading = lineHeight.resolve(size.value.takeIf { it > 0f } ?: currentSp)
        var ink = color ?: base.color
        if (opacity != null) ink = if (ink != Color.Unspecified) ink.copy(alpha = ink.alpha * opacity)
            else Color.Black.copy(alpha = opacity)
        var shift = base.baselineShift
        if (verticalShift != 0) {
            val parentPx = currentSp * density
            val offset = if (verticalShift > 0) parentPx / 3f + 1f else -(parentPx / 5f + 1f)
            val ownPx = size.value.takeIf { it > 0f }?.times(density) ?: 0f
            if (ownPx > 0f) shift = BaselineShift(offset / ownPx)
        }
        return base.copy(color = ink, fontSize = size, lineHeight = leading, fontWeight = weight ?: base.fontWeight,
            fontStyle = if (italic) FontStyle.Italic else base.fontStyle,
            textDecoration = if (underline) TextDecoration.Underline else base.textDecoration,
            baselineShift = shift, textAlign = align ?: base.textAlign,
            fontFeatureSettings = if (fontFeatures == "normal") null else fontFeatures ?: base.fontFeatureSettings)
    }

    companion object {
        fun read(css: Map<String, String>): GlossaryTypography = read { css[it.name] }

        internal fun read(css: NativeCssDeclarations): GlossaryTypography = read { css[it] }

        private inline fun read(value: (NativeCssProperty) -> String?): GlossaryTypography {
            val color = value(NativeCssProperty.Color)?.let {
                parseCssColor2(if (it.contains("color-mix", ignoreCase = true)) blendColorMix(it) ?: it else it)
            }
            val weight = when (val raw = value(NativeCssProperty.FontWeight)) {
                "bold", "bolder" -> FontWeight.Bold
                "normal" -> FontWeight.Normal
                else -> raw?.toIntOrNull()?.takeIf { it in 1..1000 }?.let(::FontWeight)
            }
            val opacity = value(NativeCssProperty.Opacity)?.trim()?.toFloatOrNull()?.let {
                when {
                    it in 0f..1f -> it
                    it > 1f && it <= 100f -> (it / 100f).coerceIn(0f, 1f)
                    else -> null
                }
            }
            val shift = when (value(NativeCssProperty.VerticalAlign)?.trim()?.lowercase()) { "super" -> 1; "sub" -> -1; else -> 0 }
            val align = when (value(NativeCssProperty.TextAlign)?.trim()?.lowercase()) {
                "right", "end" -> TextAlign.End
                "center" -> TextAlign.Center
                "left", "start" -> TextAlign.Start
                "justify" -> TextAlign.Justify
                else -> null
            }
            return GlossaryTypography(color, length(value(NativeCssProperty.FontSize), false), length(value(NativeCssProperty.LineHeight), true),
                value(NativeCssProperty.LineHeight) != null, weight, value(NativeCssProperty.FontStyle) == "italic",
                value(NativeCssProperty.TextDecoration)?.contains("underline") == true || value(NativeCssProperty.TextDecorationLine)?.contains("underline") == true,
                opacity, shift, align, value(NativeCssProperty.FontFeatureSettings))
        }

        private fun length(raw: String?, unitless: Boolean): TypographyLength? {
            val text = raw?.trim()?.lowercase() ?: return null
            return when {
                text.endsWith("px") -> text.dropLast(2).toFloatOrNull()?.let { TypographyLength(it, false) }
                text.endsWith("rem") -> text.dropLast(3).toFloatOrNull()?.let { TypographyLength(it * 16f, false) }
                text.endsWith("em") -> text.dropLast(2).toFloatOrNull()?.let { TypographyLength(it, true) }
                text.endsWith("%") -> text.dropLast(1).toFloatOrNull()?.let { TypographyLength(it / 100f, true) }
                unitless -> text.toFloatOrNull()?.let { TypographyLength(it, true) }
                else -> null
            }
        }
    }
}

public fun applyTypography(base: TextStyle, css: Map<String, String>, density: Float = 1f): TextStyle =
    GlossaryTypography.read(css).apply(base, density)

/** The rt UA defaults override inherited line-height, but not explicit dictionary declarations. */
internal fun rubyAnnotationStyle(base: TextStyle, inherited: TextStyle, css: NativeCssDeclarations): TextStyle {
    val baseSize = fontSizeSp(base)
    val size = css[NativeCssProperty.FontSize]?.let { parseMarginValue(it, baseSize) } ?: baseSize * 0.5f
    // Match WebView's minimum logical font size for relative annotation sizes.
    return inherited.copy(fontSize = size.coerceAtLeast(8f).sp,
        lineHeight = if (css[NativeCssProperty.LineHeight] != null) inherited.lineHeight else TextUnit.Unspecified,
        textDecoration = null)
}
