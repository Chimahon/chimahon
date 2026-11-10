package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Immutable native-generated commands; hit testing, selection and text are native too. */
internal class NativeCommandSurface(
    private val drawing: NativePortableDrawing,
    private val source: Int,
    private val scale: Float,
    override val width: Int,
    override val height: Int,
    override val x: Int = 0,
    override val y: Int = 0,
    private val headword: String? = null,
    private val textColor: Int? = null,
) : NativeGlossaryItem {
    private val viewport = Rect()
    private var raster: Bitmap? = null
    private val rasterBounds = Rect()
    private val bitmapSource = Rect()
    // 1:1 blit: filtering would sample the same pixels and only costs time.
    private val bitmapPaint = Paint()
    private var rasterColor: Int? = null
    val hasStaleFonts: Boolean get() = false
    val text: String by lazy { drawing.text(source) }

    override fun moved(dx: Int, dy: Int) = NativeCommandSurface(drawing, source, scale, width, height,
        x + dx, y + dy, headword, textColor)

    fun asHeadword(expression: String) = NativeCommandSurface(drawing, source, scale, width, height,
        x, y, expression, textColor)

    fun tinted(color: Int) = NativeCommandSurface(drawing, source, scale, width, height,
        x, y, headword, color)

    fun lookupAt(px: Float, py: Float): String? = hitAt(px, py)?.lookup

    fun hitAt(px: Float, py: Float): NativeGlossaryHit? {
        val result = drawing.hitAt(source, (px - x) / scale, (py - y) / scale) ?: return null
        val character = result[5].toInt()
        if (headword != null) {
            if (character < 0) return null
            val char = character.toChar()
            val kanji = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
            return NativeGlossaryHit(lookup = if (kanji) char.toString() else headword, kanji = kanji)
                .also { it.surface = this; it.textOffset = result[2].toInt() }
        }
        val word = result[0]
        if (word.isEmpty()) return null
        return NativeGlossaryHit(lookup = word, sentence = result[1], sentenceOffset = result[4].toInt())
            .also { it.surface = this; it.textOffset = result[3].toInt() }
    }

    fun selectionRects(offset: Int, codePoints: Int): List<RectF> {
        val flat = drawing.selectionRects(source, offset, codePoints)
        return List(flat.size / 4) { index ->
            val left = x + flat[index * 4] * scale
            val top = y + flat[index * 4 + 1] * scale
            RectF(left, top, left + flat[index * 4 + 2] * scale, top + flat[index * 4 + 3] * scale)
        }
    }

    fun clearRaster() {
        raster = null
        rasterBounds.setEmpty()
    }

    override fun draw(canvas: Canvas, paint: Paint) {
        val checkpoint = canvas.save()
        try {
            canvas.translate(x.toFloat(), y.toFloat())
            if (headword != null) canvas.clipRect(0, 0, width, height)
            if (!canvas.getClipBounds(viewport) || !viewport.intersect(0, 0, width, height)) return
            val w = viewport.width()
            val h = viewport.height()
            var bitmap = raster
            val resized = bitmap == null || bitmap.width < w || bitmap.height < h
            if (resized) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                raster = bitmap
            }
            val pixels = checkNotNull(bitmap)
            if (resized || rasterBounds != viewport || rasterColor != textColor) {
                drawing.raster(source, scale, viewport.left, viewport.top, w, h, pixels, textColor)
                rasterBounds.set(viewport)
                rasterColor = textColor
            }
            bitmapSource.set(0, 0, w, h)
            canvas.drawBitmap(pixels, bitmapSource, viewport, bitmapPaint)
        } finally { canvas.restoreToCount(checkpoint) }
    }
}
