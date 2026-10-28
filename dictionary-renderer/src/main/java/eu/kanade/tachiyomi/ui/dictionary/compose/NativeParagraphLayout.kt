package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The single width-dependent paragraph path. It consumes no CSS or mutable traversal state. */
internal class NativeParagraphLayout(private val resources: NativeGlossaryResources) {
    private class DecorationGeometry {
        val selection = Path()
        val clip = Path()
        val bounds = RectF()
    }
    private var decorationGeometry: DecorationGeometry? = null

    fun place(paragraph: NativeParagraph, x: Int, width: Int, y: Float, interactive: Boolean,
        intrinsic: Boolean, tableCell: Boolean, rubyLineHeight: Boolean,
        output: MutableList<NativeGlossaryItem>): Float {
        val text = paragraph.textForLayout()
        val profile = paragraph.profile
        val gridHeight = if (paragraph.minimumHeight > 0f)
            maxOf(paragraph.minimumHeight, profile.resource.lineHeightPx) else 0f
        val lineHeight = if (gridHeight > 0f) {
            val base = profile.resource.font.metricsInt
            NativeCssLineHeight(gridHeight, paragraph.rubyExtra,
                -base.ascent, base.descent, y == 0f,
                rubyRuns = paragraph.rubies, shiftedRuns = paragraph.shiftedRuns,
                imageOffsets = paragraph.images.map { it.start },
                lineBoxOrigin = tableCell,
                cropFirstRubyLine = !rubyLineHeight && y == 0f)
        } else null
        val layout = resources.layout(text, width, paragraph.style.textStyle, profile, lineHeight, intrinsic)
        val geometryStarted = if (resources.stageTimings) System.nanoTime() else 0L
        if (!intrinsic) {
            if (paragraph.decorations.isNotEmpty()) {
                val decorationStarted = if (resources.stageTimings) System.nanoTime() else 0L
                val geometry = decorationGeometry ?: DecorationGeometry().also { decorationGeometry = it }
                for (decoration in paragraph.decorations) {
                    val first = layout.getLineForOffset(decoration.start)
                    val last = layout.getLineForOffset(decoration.end - 1)
                    for (line in first..last) {
                        val fragmentStart = maxOf(decoration.start, layout.getLineStart(line))
                        val fragmentEnd = minOf(decoration.end, layout.getLineEnd(line))
                        if (fragmentStart >= fragmentEnd) continue
                        val path = geometry.selection
                        layout.getSelectionPath(fragmentStart, fragmentEnd, path)
                        // A soft-wrap selection fills the remainder of its line; CSS only
                        // paints the fragment's actual inline content area.
                        if (fragmentEnd == layout.getLineEnd(line) && line + 1 < layout.lineCount) {
                            val leading = layout.getPrimaryHorizontal(layout.getLineStart(line))
                            val clip = geometry.clip
                            clip.reset()
                            clip.addRect(minOf(layout.getLineLeft(line), leading), layout.getLineTop(line).toFloat(),
                                maxOf(layout.getLineRight(line), leading), layout.getLineBottom(line).toFloat(), Path.Direction.CW)
                            path.op(clip, Path.Op.INTERSECT)
                        }
                        if (path.isEmpty) continue
                        path.computeBounds(geometry.bounds, true)
                        val baseline = layout.getLineTop(line) - layout.getLineAscent(line)
                        val top = baseline + decoration.ascent
                        val bottom = baseline + decoration.descent
                        output.add(NativeGlossaryRect(x + geometry.bounds.left.roundToInt() - decoration.paddingStart,
                            (y + top - decoration.paddingTop).roundToInt(),
                            ceil(geometry.bounds.width()).toInt() + decoration.paddingStart + decoration.paddingEnd,
                            (bottom - top).roundToInt() + decoration.paddingTop + decoration.paddingBottom,
                            decoration.background, decoration.borderColor, decoration.stroke, decoration.radius))
                    }
                }
                if (resources.stageTimings) resources.decorationNanos += System.nanoTime() - decorationStarted
            }
            for (image in paragraph.images) {
                val line = layout.getLineForOffset(image.start)
                val primary = layout.getPrimaryHorizontal(image.start)
                val left = if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) primary - image.width else primary
                output.add(NativeGlossaryImage((x + left).roundToInt(),
                    (y + layout.getLineBaseline(line) + image.shift - image.height).roundToInt(),
                    image.width, image.height, image.node))
            }
        }
        val rubyStarted = if (resources.stageTimings) System.nanoTime() else 0L
        output.add(NativeTextFlow.prepare(layout, x, y.roundToInt(), interactive, paragraph.rubies,
            prepareRubyDrawing = !intrinsic))
        if (resources.stageTimings) resources.rubyPlacementNanos += System.nanoTime() - rubyStarted
        var bottom = y + layout.height
        // Preserve fractional CSS line-height advance independently of integer layout bounds.
        if (!paragraph.hasShiftedText && paragraph.images.isEmpty() &&
            (!rubyLineHeight || paragraph.rubyExtra == 0) && gridHeight > 0f) {
            val exactHeight = gridHeight * layout.lineCount
            bottom += exactHeight - exactHeight.roundToInt()
        }
        if (resources.stageTimings) resources.paragraphGeometryNanos += System.nanoTime() - geometryStarted
        return bottom
    }
}
