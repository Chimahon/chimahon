package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.SpannableStringBuilder
import android.text.Spanned

/** Finalized text and inline facts. The caller resolves width-sensitive images before assembly. */
internal class NativeParagraph(
    private val content: CharSequence,
    val style: NativeTextStyle,
    val profile: NativeTextProfile,
    val minimumHeight: Float,
    val shiftedRuns: List<NativeShiftedRun>,
    val rubies: List<NativeRubySpan>,
    val rubyExtra: Int,
    val hasShiftedText: Boolean,
    val decorations: List<NativeInlineDecoration>,
    val images: List<NativeInlineImage>,
) {
    private var laidOut = false

    /** Android owns placement-specific line-height spans; never mutate an earlier layout. */
    fun textForLayout(): CharSequence {
        if (content !is Spanned) return content
        if (!laidOut) {
            laidOut = true
            return content
        }
        return SpannableStringBuilder(content).also { text ->
            for (span in text.getSpans(0, text.length, NativeCssLineHeight::class.java)) text.removeSpan(span)
        }
    }
}

/** Pixel/color facts are resolved during lowering, not parsed again for every wrapped fragment. */
internal data class NativeInlineDecoration(
    val start: Int,
    val end: Int,
    val ascent: Float,
    val descent: Float,
    val paddingStart: Int,
    val paddingEnd: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val background: Int,
    val borderColor: Int,
    val stroke: Float,
    val radius: Float,
)

internal data class NativeInlineImage(
    val start: Int,
    val width: Int,
    val height: Int,
    val shift: Int,
    val node: StructuredNode.Element,
)
