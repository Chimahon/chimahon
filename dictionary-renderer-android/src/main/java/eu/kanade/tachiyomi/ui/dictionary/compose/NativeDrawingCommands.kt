package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import chimahon.renderer.DictionaryCore
import java.nio.ByteBuffer

public data class NativeDrawingHitProbe(val x: Float, val y: Float, val category: String,
    val expected: String?, val movedLookup: String?)

/** Immutable native-generated commands; this consumer does not measure or lay out text. */
internal class NativeCommandSurface(
    private val commands: ByteBuffer,
    private val hits: ByteBuffer,
    private val requests: IntArray,
    private val contexts: IntArray,
    private val characters: CharArray,
    private val drawing: NativePortableDrawing,
    private val source: Int,
    private val firstCommand: Int,
    private val commandCount: Int,
    private val firstHit: Int,
    private val hitCount: Int,
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
    val text: String by lazy {
        buildString {
            for (i in contexts.indices step 4) {
                if (contexts[i] != source || contexts[i + 3] and 1 != 0) continue
                if (isNotEmpty()) append('\n')
                append(characters, contexts[i + 1], contexts[i + 2])
            }
        }
    }

    override fun moved(dx: Int, dy: Int): NativeGlossaryItem = NativeCommandSurface(commands, hits, requests,
        contexts, characters, drawing, source, firstCommand, commandCount, firstHit, hitCount, scale, width, height, x + dx, y + dy, headword, textColor)

    fun asHeadword(expression: String): NativeCommandSurface = NativeCommandSurface(commands, hits, requests,
        contexts, characters, drawing, source, firstCommand, commandCount, firstHit, hitCount, scale, width, height, x, y, expression, textColor)

    fun tinted(color: Int): NativeCommandSurface = NativeCommandSurface(commands, hits, requests,
        contexts, characters, drawing, source, firstCommand, commandCount, firstHit, hitCount, scale, width, height, x, y, headword, color)

    fun lookupAt(px: Float, py: Float): String? = hitAt(px, py)?.lookup

    private fun bodyContextAt(offset: Int): Int {
        for (i in contexts.indices step 4) {
            if (contexts[i] == source && contexts[i + 3] and 1 == 0 &&
                offset >= contexts[i + 1] && offset < contexts[i + 1] + contexts[i + 2]) return i
        }
        return -1
    }

    fun hitAt(px: Float, py: Float): NativeGlossaryHit? {
        val localX = (px - x) / scale
        val localY = (py - y) / scale
        for (index in firstHit + hitCount - 1 downTo firstHit) {
            val at = index * 28
            val left = hits.getFloat(at + 12)
            val top = hits.getFloat(at + 16)
            if (localX < left || localY < top || localX >= left + hits.getFloat(at + 20) || localY >= top + hits.getFloat(at + 24)) continue
            val offset = hits.getInt(at + 8)
            if (headword != null) {
                val char = characters[offset]
                val kanji = Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
                return NativeGlossaryHit(lookup = if (kanji) char.toString() else headword, kanji = kanji)
                    .also { it.surface = this; it.textOffset = offset }
            }
            // Requests are font/style runs, not lookup boundaries. Use the paragraph's
            // original body context, which already excludes ruby annotations.
            val context = bodyContextAt(offset)
            if (context < 0) return null
            val bodyStart = contexts[context + 1]
            val body = String(characters, bodyStart, contexts[context + 2])
            val local = offset - bodyStart
            val word = DictionaryCore.lookupWordAt(body, local) ?: return null
            val lookupStart = body.lastIndexOf(word, local).takeIf { it >= 0 && local < it + word.length } ?: local
            val delimiters = "。！？.!?\n\r"
            var sentenceStart = local
            while (sentenceStart > 0 && body[sentenceStart - 1] !in delimiters) sentenceStart--
            var sentenceEnd = local
            while (sentenceEnd < body.length && body[sentenceEnd] !in delimiters) sentenceEnd++
            if (sentenceEnd < body.length) sentenceEnd++
            while (sentenceStart < local && body[sentenceStart].isWhitespace()) sentenceStart++
            return NativeGlossaryHit(lookup = word, sentence = body.substring(sentenceStart, sentenceEnd).trimEnd(),
                sentenceOffset = local - sentenceStart).also { it.surface = this; it.textOffset = bodyStart + lookupStart }
        }
        return null
    }

    fun selectionRects(offset: Int, codePoints: Int): List<RectF> {
        val context = bodyContextAt(offset)
        if (context < 0) return emptyList()
        val limit = contexts[context + 1] + contexts[context + 2]
        val count = Character.codePointCount(characters, offset, limit - offset)
        val end = Character.offsetByCodePoints(characters, offset, limit - offset, offset, codePoints.coerceIn(0, count))
        return buildList {
            for (index in firstHit until firstHit + hitCount) {
                val at = index * 28
                if (hits.getInt(at + 8) !in offset until end) continue
                val left = x + hits.getFloat(at + 12) * scale
                val top = y + hits.getFloat(at + 16) * scale
                add(RectF(left, top, left + hits.getFloat(at + 20) * scale, top + hits.getFloat(at + 24) * scale))
            }
        }
    }

    fun hitProbes(): List<NativeDrawingHitProbe> {
        val result = ArrayList<NativeDrawingHitProbe>()
        val moved = moved(11, 17) as NativeCommandSurface
        var ordinary = 0
        var ruby = 0
        for (index in firstHit until firstHit + hitCount) {
            val at = index * 28
            val offset = hits.getInt(at + 8)
            if (!characters[offset].isLetter()) continue
            val request = hits.getInt(at + 4)
            val context = bodyContextAt(offset)
            if (context < 0) continue
            val expected = DictionaryCore.lookupWordAt(String(characters, contexts[context + 1], contexts[context + 2]), offset - contexts[context + 1]) ?: continue
            var isRuby = false
            for (i in firstCommand until firstCommand + commandCount) {
                val c = i * 64
                if (commands.getInt(c) != 0 || commands.getInt(c + 8) != request) continue
                if (offset in commands.getInt(c + 12) until commands.getInt(c + 12) + commands.getInt(c + 16)) {
                    isRuby = commands.getInt(c + 60) and 8 != 0
                    break
                }
            }
            if (isRuby) { if (ruby++ >= 128) continue } else if (ordinary++ >= 128) continue
            val px = x + (hits.getFloat(at + 12) + hits.getFloat(at + 20) / 2) * scale
            val py = y + (hits.getFloat(at + 16) + hits.getFloat(at + 24) / 2) * scale
            result.add(NativeDrawingHitProbe(px, py, if (isRuby) "ruby" else "ordinary", expected, moved.lookupAt(px + 11, py + 17)))
        }
        var annotations = 0
        var static = 0
        for (index in firstCommand until firstCommand + commandCount) {
            val at = index * 64
            if (commands.getInt(at) != 0) continue
            val flags = commands.getInt(at + 60)
            if (flags and 4 == 0) continue
            val annotation = flags and 2 != 0
            if (annotation) { if (annotations++ >= 128) continue } else if (static++ >= 128) continue
            val px = x + (commands.getFloat(at + 24) + commands.getFloat(at + 32) / 2) * scale
            val py = y + (commands.getFloat(at + 28) + commands.getFloat(at + 36) / 2) * scale
            result.add(NativeDrawingHitProbe(px, py, if (annotation) "annotation" else "static", null, moved.lookupAt(px + 11, py + 17)))
        }
        return result
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
