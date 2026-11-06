package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
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
    val profilingId: Long,
) {
    val images = items.filterIsInstance<NativeGlossaryImage>()
    val disclosures = items.filterIsInstance<NativeGlossaryDisclosure>()
    val commandSurfaces: List<NativeGlossaryItem> = items.filterIsInstance<NativeCommandSurface>()
    fun drawingHitProbes(): List<NativeDrawingHitProbe> = items.filterIsInstance<NativeCommandSurface>().flatMap { it.hitProbes() }
    val hasStaleFonts: Boolean get() = items.any { it is NativeCommandSurface && it.hasStaleFonts }
    val text: String by lazy {
        items.mapNotNull { item -> when (item) {
            is NativeCommandSurface -> item.text
            else -> null
        } }.joinToString("\n")
    }
    fun hit(x: Float, y: Float): NativeGlossaryHit? {
        for (item in items.asReversed()) {
            if (x < item.x || y < item.y || x >= item.x + item.width || y >= item.y + item.height) continue
            if (item is NativeGlossaryDisclosure) return NativeGlossaryHit(disclosure = item.source)
            if (item is NativeCommandSurface) item.lookupAt(x, y)?.let { return NativeGlossaryHit(lookup = it) }
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
