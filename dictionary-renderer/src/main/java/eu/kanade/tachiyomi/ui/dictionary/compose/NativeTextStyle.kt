package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/** Worker-owned computed style. Traversal carries this identity instead of hashing TextStyle. */
internal class NativeTextStyle(val textStyle: TextStyle, private val owner: NativeGlossaryResources) {
    val fontSize = textStyle.fontSize
    val lineHeight = textStyle.lineHeight
    val baselineShift = textStyle.baselineShift
    val textAlign = textStyle.textAlign
    val color = textStyle.color
    val sizeSp = if (fontSize.isSp) fontSize.value else 16f
    val profile: NativeTextProfile by lazy(LazyThreadSafetyMode.NONE) { owner.createProfile(textStyle) }

    fun copy(
        fontWeight: FontWeight? = textStyle.fontWeight,
        fontStyle: FontStyle? = textStyle.fontStyle,
        textDecoration: TextDecoration? = textStyle.textDecoration,
        fontSize: TextUnit = this.fontSize,
        lineHeight: TextUnit = this.lineHeight,
        baselineShift: BaselineShift? = this.baselineShift,
        background: Color = textStyle.background,
    ): NativeTextStyle = owner.nativeStyle(textStyle.copy(fontWeight = fontWeight, fontStyle = fontStyle,
        textDecoration = textDecoration, fontSize = fontSize, lineHeight = lineHeight,
        baselineShift = baselineShift, background = background))
}

internal fun fontSizeSp(style: NativeTextStyle): Float = style.sizeSp
