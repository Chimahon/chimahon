package eu.kanade.tachiyomi.ui.dictionary.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.dictionary.compose.HEADWORD_RUBY_GAP_EM
import eu.kanade.tachiyomi.ui.dictionary.compose.HEADWORD_RUBY_SIZE_RATIO
import eu.kanade.tachiyomi.ui.dictionary.compose.HeadwordUnit
import eu.kanade.tachiyomi.ui.dictionary.compose.RubyGlyphBatchBuilder
import eu.kanade.tachiyomi.ui.dictionary.compose.RubyParagraphDrawing
import eu.kanade.tachiyomi.ui.dictionary.compose.RubyTextRenderer
import eu.kanade.tachiyomi.ui.dictionary.compose.column
import eu.kanade.tachiyomi.ui.dictionary.compose.standaloneBox
import eu.kanade.tachiyomi.ui.dictionary.compose.rubyReadingStyle
import eu.kanade.tachiyomi.ui.dictionary.compose.splitHeadwordUnits
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeCardElement
import eu.kanade.tachiyomi.ui.dictionary.compose.nativeCardSource
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeCoreCompiler
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingInput
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONArray

/** The same header tree through typed transport and JSON must paint and hit identically. */
internal fun typedCardTransportCheck(core: NativeCoreCompiler, style: TextStyle, width: Int): JSONObject {
    fun element(tag: String, content: Any, css: String = "") =
        NativeCardElement(tag, content, if (css.isBlank()) emptyMap() else mapOf("style" to css))
    val tree = listOf(element("div", listOf(
        element("ruby", listOf("絶", element("rt", "ぜつ"))),
        element("ruby", listOf("望", element("rt", "ぼう")))), "font-size:1.6em;font-weight:bold;white-space:nowrap"),
        element("div", listOf(element("span", "v1", "background:#666;color:white;padding:2px 5px"), " noun")),
        element("div", "▾ Jitendex", "font-size:.94em"),
        NativeCardElement("img", "", properties = mapOf("path" to "data:image/svg+xml;base64,PHN2Zy8+",
            "width" to "36", "height" to "34", "sizeUnits" to "px")))
    fun json(value: Any): Any = when (value) {
        is String -> value
        is List<*> -> JSONArray(value.map { json(requireNotNull(it)) })
        is NativeCardElement -> JSONObject().put("tag", value.tag).put("content", json(value.content)).apply {
            if (value.attributes.isNotEmpty()) put("htmlAttributes", JSONObject(value.attributes))
            value.properties.forEach { (name, text) -> put(name, text) }
        }
        else -> error("Invalid header fixture")
    }
    val source = nativeCardSource(tree)
    val css = "body{margin:0;line-height:1.4}rt{font-size:55%;line-height:1}"
    val docs = core.prepareBatch(listOf(NativeDrawingInput(json(tree).toString(), css, width),
        NativeDrawingInput("", css, width, source)), style)
    check(docs[0].width == docs[1].width && docs[0].height == docs[1].height && docs[0].text == docs[1].text)
    val bitmaps = docs.map { doc -> Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.WHITE)
        val canvas = Canvas(this)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        doc.items.forEach { it.draw(canvas, paint) }
    } }
    val equal = bitmaps[0].sameAs(bitmaps[1])
    bitmaps.forEach { it.recycle() }
    check(equal) { "Typed card transport changed painted output" }
    val images = docs.map { doc -> doc.images.map { listOf(it.x, it.y, it.width, it.height) } }
    check(images[0] == images[1]) { "Typed card transport changed image placement" }
    val probes = docs[0].drawingHitProbes()
    val mismatches = probes.count { docs[0].hit(it.x, it.y)?.lookup != docs[1].hit(it.x, it.y)?.lookup }
    check(mismatches == 0) { "Typed card transport changed recursive hits" }
    return JSONObject().put("identical_pixels", equal).put("hit_probes", probes.size)
        .put("hit_mismatches", mismatches).put("image_geometry_equal", true)
}

/** Actual glyph/clip regression check; not a capture of the Compose popup chrome. */
internal fun headwordGeometryCheck(context: Context, fontFile: File, root: File,
    expression: String, reading: String, density: Float, fontSize: Float, color: Color): JSONObject {
    val renderer = RubyTextRenderer(Density(density, 1f), LayoutDirection.Ltr, createFontFamilyResolver(context))
    val size = fontSize
    val style = TextStyle(color = color, fontFamily = FontFamily(Typeface.createFromFile(fontFile)),
        fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontWeight = FontWeight.Bold)
    val annotation = rubyReadingStyle(style, HEADWORD_RUBY_SIZE_RATIO)
    val columns = splitHeadwordUnits(expression, reading).map { unit ->
        require(unit is HeadwordUnit.Kanji && unit.reading.isNotBlank()) { "Check requires annotated kanji units" }
        renderer.column(unit.char, unit.reading, style, annotation)
    }
    val boxes = columns.map { it.standaloneBox() }
    val oldHeights = columns.map { ceil(it.heightPx + it.baseLayout.size.height - it.baseLayout.firstBaseline).toInt() }
    val gap = (size * HEADWORD_RUBY_GAP_EM * density).roundToInt()
    fun capture(old: Boolean, baseOnly: Boolean, clipped: Boolean = true, padding: Int = 0): Pair<Bitmap, Int> {
        val heights = if (old) oldHeights else boxes.map { it.height }
        val widths = if (old) columns.map { it.widthPx } else boxes.map { it.width }
        val width = widths.sumOf { gap + it }
        val height = heights.max()
        val bitmap = Bitmap.createBitmap(width + padding * 2, height + padding * 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val canvas = Canvas(bitmap)
        canvas.translate(padding.toFloat(), padding.toFloat())
        var x = 0
        columns.forEachIndexed { index, column ->
            x += gap
            val y = height - heights[index]
            val checkpoint = canvas.save()
            if (clipped) canvas.clipRect(x, y, x + widths[index], y + heights[index])
            val top = y - if (old) 0f else boxes[index].top
            val left = x - if (old) 0f else boxes[index].left
            if (baseOnly) {
                val builder = RubyGlyphBatchBuilder()
                column.baseLayout.appendTo(builder,
                    left + (column.widthPx - column.baseLayout.advancePx) / 2f, top + column.baseOffsetPx)
                builder.build().forEach { it.draw(canvas) }
            } else RubyParagraphDrawing.column(column).draw(canvas, left, top)
            canvas.restoreToCount(checkpoint)
            x += widths[index]
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return bitmap to pixels.count { it != android.graphics.Color.WHITE }
    }
    val oldBase = capture(true, true).let { (bitmap, ink) -> bitmap.recycle(); ink }
    val newBase = capture(false, true).let { (bitmap, ink) -> bitmap.recycle(); ink }
    // Equal placement/raster phase, but a padded Canvas with no unit clips is the oracle.
    val (reference, referenceInk) = capture(false, false, clipped = false, padding = 32)
    val (bounded, _) = capture(false, false, padding = 32)
    var clippedPixels = 0
    for (y in 0 until reference.height) for (x in 0 until reference.width) {
        if (reference.getPixel(x, y) != bounded.getPixel(x, y)) clippedPixels++
    }
    reference.recycle()
    bounded.recycle()
    for (old in listOf(true, false)) {
        val (bitmap, _) = capture(old, false)
        File(root, if (old) "headword-before.png" else "headword-after.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    check(newBase > oldBase) { "Expected restored kanji ink: before=$oldBase after=$newBase" }
    check(clippedPixels == 0) { "Headword still clips $clippedPixels pixels against the unbounded render" }
    return JSONObject().put("scope", "headword-glyphs-and-clipping/not-popup-chrome")
        .put("expression", expression).put("reading", reading).put("font_size", size)
        .put("old_heights", JSONArray(oldHeights)).put("new_heights", JSONArray(boxes.map { it.height }))
        .put("old_widths", JSONArray(columns.map { it.widthPx })).put("new_widths", JSONArray(boxes.map { it.width }))
        .put("unbounded_ink", referenceInk).put("clipped_pixels", clippedPixels)
        .put("old_base_ink", oldBase).put("new_base_ink", newBase)
}
