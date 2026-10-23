package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Finished positions only. No source-tree, CSS, measurement or recording work during drawing. */
public sealed interface NativeGlossaryItem {
    val x: Int
    val y: Int
    val width: Int
    val height: Int
    fun moved(dx: Int, dy: Int): NativeGlossaryItem
    fun draw(canvas: Canvas, paint: Paint)
}

public data class NativeGlossaryRect(
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val background: Int = 0,
    val border: Int = 0,
    val stroke: Float = 0f,
    val radius: Float = 0f,
    val fill: android.graphics.Shader? = null,
    /** Collapsed table edges: top=1, right=2, bottom=4, left=8. Zero uses an inset outline. */
    val collapsedEdges: Int = 0,
) : NativeGlossaryItem {
    override fun moved(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)
    override fun draw(canvas: Canvas, paint: Paint) {
        paint.style = Paint.Style.FILL
        if (background ushr 24 != 0) {
            paint.color = background
            canvas.drawRoundRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), radius, radius, paint)
        }
        if (fill != null) {
            val checkpoint = canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            paint.color = android.graphics.Color.WHITE
            paint.shader = fill
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.shader = null
            canvas.restoreToCount(checkpoint)
        }
        if (stroke > 0f && border ushr 24 != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = border
            if (collapsedEdges == 0) {
                val inset = stroke / 2f
                canvas.drawRoundRect(x + inset, y + inset, x + width - inset, y + height - inset, radius, radius, paint)
            } else {
                val left = x.toFloat()
                val top = y.toFloat()
                val right = (x + width).toFloat()
                val bottom = (y + height).toFloat()
                if (collapsedEdges and 1 != 0) canvas.drawLine(left, top, right, top, paint)
                if (collapsedEdges and 2 != 0) canvas.drawLine(right, top, right, bottom, paint)
                if (collapsedEdges and 4 != 0) canvas.drawLine(left, bottom, right, bottom, paint)
                if (collapsedEdges and 8 != 0) canvas.drawLine(left, top, left, bottom, paint)
            }
            paint.style = Paint.Style.FILL
        }
    }
}

public data class NativeGlossaryImage(
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val source: StructuredNode.Element,
) : NativeGlossaryItem {
    override fun moved(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)
    override fun draw(canvas: Canvas, paint: Paint) = Unit
}

public data class NativeGlossaryDisclosure(
    override val x: Int,
    override val y: Int,
    override val width: Int,
    override val height: Int,
    val source: StructuredNode.Element,
) : NativeGlossaryItem {
    override fun moved(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)
    override fun draw(canvas: Canvas, paint: Paint) = Unit
}

public data class NativeGlossaryHit(val lookup: String? = null, val disclosure: StructuredNode.Element? = null)

public class NativeGlossaryDocument(
    val nodes: List<StructuredNode>,
    val css: ParsedCss,
    val style: TextStyle,
    val ancestors: List<CssAncestor>,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val secondary: Color,
    val border: Color,
    val width: Int,
    val height: Int,
    val items: List<NativeGlossaryItem>,
    private val resources: NativeGlossaryResources,
    val profilingId: Long,
) {
    val images = items.filterIsInstance<NativeGlossaryImage>()
    val disclosures = items.filterIsInstance<NativeGlossaryDisclosure>()
    val hasStaleFonts: Boolean get() = resources.hasStaleFonts
    val text: String by lazy { items.filterIsInstance<NativeTextFlow>().joinToString("\n") { it.layout.text.toString() } }
    val links: List<String> by lazy {
        items.filterIsInstance<NativeTextFlow>().filter { it.interactive }.flatMap { flow ->
            val text = flow.layout.text as? Spanned
            text?.getSpans(0, text.length, NativeLookupLink::class.java)?.map { it.target } ?: emptyList()
        }.distinct()
    }

    fun hit(x: Float, y: Float): NativeGlossaryHit? {
        for (item in items.asReversed()) {
            if (x < item.x || y < item.y || x >= item.x + item.width || y >= item.y + item.height) continue
            if (item is NativeGlossaryDisclosure) return NativeGlossaryHit(disclosure = item.source)
            if (item is NativeTextFlow) item.lookupAt(x, y)?.let { return NativeGlossaryHit(lookup = it) }
        }
        return null
    }

    fun matches(nodes: List<StructuredNode>, css: ParsedCss, style: TextStyle, ancestors: List<CssAncestor>,
        density: Density, direction: LayoutDirection, resolver: FontFamily.Resolver, secondary: Color,
        border: Color, width: Int): Boolean =
        this.nodes === nodes && this.css === css && this.style == style && this.ancestors == ancestors &&
            this.density == density && this.direction == direction && this.resolver === resolver &&
            this.secondary == secondary && this.border == border && this.width == width && !hasStaleFonts
}
