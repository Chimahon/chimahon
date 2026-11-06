package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.viewinterop.AndroidView
import chimahon.renderer.DictionaryCore
import chimahon.diagnostics.LookupProfiler
import coil3.compose.AsyncImage
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Already shaped fragments; retaining their scopes prevents dictionary styles leaking into chrome. */
public class NativeCardFragment(val document: NativeGlossaryDocument, val dictionary: String = "",
    val left: Int = 0, val before: Int = 0, val after: Int = 0,
    val action: StructuredNode.Element? = null, val headword: String? = null,
    val background: Int = 0, val stripe: Int = 0, val tint: Int? = null)

private data class CardFill(override val x: Int, override val y: Int,
    override val width: Int, override val height: Int, val color: Int) : NativeGlossaryItem {
    override fun moved(dx: Int, dy: Int) = copy(x = x + dx, y = y + dy)
    override fun draw(canvas: Canvas, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), paint)
    }
}

/** One native placement pass; toggles reuse glyphs, CSS and measured glossary content. */
public fun placeNativeCard(fragments: List<NativeCardFragment>, visible: BooleanArray,
    width: Int, profilingId: Long, separator: Int = 0): NativeGlossaryDocument {
    require(fragments.isNotEmpty() && fragments.size == visible.size)
    val geometry = FloatArray(fragments.size * 6)
    fragments.forEachIndexed { i, fragment ->
        val at = i * 6
        geometry[at] = fragment.document.height.toFloat()
        geometry[at + 1] = fragment.document.width.toFloat()
        geometry[at + 2] = fragment.left.toFloat()
        geometry[at + 3] = fragment.before.toFloat()
        geometry[at + 4] = fragment.after.toFloat()
        geometry[at + 5] = if (visible[i]) 1f else 0f
    }
    val placed = LookupProfiler.section(profilingId, "native.card.place") { DictionaryCore.placeCard(geometry) }
    val items = ArrayList<NativeGlossaryItem>()
    if (separator ushr 24 != 0) items.add(CardFill(0, 0, width,
        (2 * fragments.first().document.density.density).roundToInt().coerceAtLeast(1), separator))
    fragments.forEachIndexed { i, fragment ->
        if (!visible[i]) return@forEachIndexed
        val x = placed[i * 4].roundToInt()
        val y = placed[i * 4 + 1].roundToInt()
        if (fragment.background ushr 24 != 0) items.add(CardFill(x, y, fragment.document.width,
            fragment.document.height, fragment.background))
        if (fragment.stripe ushr 24 != 0) items.add(CardFill(x, y,
            (4 * fragment.document.density.density).roundToInt(), fragment.document.height, fragment.stripe))
        fragment.document.items.forEach { item ->
            val surface = if (item is NativeCommandSurface && fragment.tint != null) item.tinted(fragment.tint) else item
            val moved = (if (surface is NativeCommandSurface && fragment.headword != null)
                surface.asHeadword(fragment.headword) else surface).moved(x, y)
            items.add(if (moved is NativeGlossaryImage) moved.copy(source = moved.source.copy(
                attributes = moved.source.attributes.copy(properties = moved.source.attributes.properties +
                    ("cardDictionary" to fragment.dictionary)))) else moved)
        }
        fragment.action?.let { items.add(NativeGlossaryDisclosure(x, y, fragment.document.width,
            fragment.document.height, it)) }
    }
    val first = fragments.first().document
    return NativeGlossaryDocument(first.nodes, first.css, first.style, first.ancestors,
        first.density, first.direction, first.resolver, first.secondary, first.border, width,
        ceil(placed.last()).toInt().coerceAtLeast(1), items, profilingId)
}

/** A single drawing view per card; only images and real action controls remain Compose children. */
@Composable
public fun NativeDocumentContent(document: NativeGlossaryDocument, dictionary: String,
    onLookup: ((String) -> Unit)?, onAction: (StructuredNode.Element) -> Unit,
    modifier: Modifier = Modifier, onLongAction: ((StructuredNode.Element) -> Unit)? = null,
    onKanjiLookup: ((String) -> Unit)? = null) {
    val media = LocalMediaDataUris.current
    Layout(modifier = modifier, content = {
        AndroidView(factory = { NativeGlossaryView(it) }, update = {
            it.bind(document, dictionary, onLookup, onAction, onLongAction, onKanjiLookup)
        })
        document.images.forEach { image ->
            val props = image.source.attributes.properties
            AsyncImage(model = resolveMediaUri2(props["cardDictionary"] ?: dictionary, props["path"].orEmpty(), media),
                contentDescription = props["title"])
        }
    }) { children, bounds ->
        val body = children.first().measure(Constraints.fixed(document.width, document.height))
        val images = document.images.mapIndexed { index, image ->
            children[index + 1].measure(Constraints.fixed(image.width, image.height))
        }
        layout(bounds.constrainWidth(document.width), bounds.constrainHeight(document.height)) {
            body.place(0, 0)
            images.forEachIndexed { index, image -> image.place(document.images[index].x, document.images[index].y) }
        }
    }
}
