package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.State
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
    private val fonts: Array<NativePlatformFont>,
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
    private val gradients: MutableMap<Int, Shader> = HashMap(),
) : NativeGlossaryItem {
    private val viewport = Rect()
    private val clip = Path()
    private val borderRadii = FloatArray(8)
    val hasStaleFonts: Boolean get() = fonts.any { it.state.value !== it.typeface }
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
        contexts, characters, fonts, source, firstCommand, commandCount, firstHit, hitCount, scale, width, height, x + dx, y + dy, gradients)

    fun lookupAt(px: Float, py: Float): String? {
        val localX = (px - x) / scale
        val localY = (py - y) / scale
        for (index in firstHit + hitCount - 1 downTo firstHit) {
            val at = index * 28
            val left = hits.getFloat(at + 12)
            val top = hits.getFloat(at + 16)
            if (localX < left || localY < top || localX >= left + hits.getFloat(at + 20) || localY >= top + hits.getFloat(at + 24)) continue
            val request = hits.getInt(at + 4) * 5
            val start = requests[request + 3]
            val count = requests[request + 4]
            return lookupWordAt(String(characters, start, count), hits.getInt(at + 8) - start)
        }
        return null
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
            val context = request * 5
            val expected = lookupWordAt(String(characters, requests[context + 3], requests[context + 4]), offset - requests[context + 3]) ?: continue
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

    override fun draw(canvas: Canvas, paint: Paint) {
        val checkpoint = canvas.save()
        try {
            canvas.translate(x.toFloat(), y.toFloat())
            canvas.scale(scale, scale)
            canvas.getClipBounds(viewport)
            for (index in firstCommand until firstCommand + commandCount) {
                val at = index * 64
                val kind = commands.getInt(at)
                if (kind == 2) continue // Images use the existing Coil/SVG surface.
                val left = commands.getFloat(at + 24)
                val top = commands.getFloat(at + 28)
                val right = left + commands.getFloat(at + 32)
                val bottom = top + commands.getFloat(at + 36)
                if (top >= viewport.bottom || bottom <= viewport.top || left >= viewport.right || right <= viewport.left) continue
                if (kind == 0) {
                    val request = commands.getInt(at + 8) * 5
                    val font = fonts[commands.getInt(at + 20)].paint
                    font.color = commands.getInt(at + 44)
                    val trimmed = commands.getInt(at + 60) and 16 != 0
                    if (trimmed) font.fontFeatureSettings = "'halt' 1"
                    canvas.drawTextRun(characters, commands.getInt(at + 12), commands.getInt(at + 16),
                        requests[request + 3], requests[request + 4], left, commands.getFloat(at + 40),
                        commands.getInt(at + 60) and 1 != 0, font)
                    if (trimmed) font.fontFeatureSettings = null
                } else if (kind == 3) {
                    paint.style = Paint.Style.FILL
                    paint.color = -1
                    paint.shader = gradients.getOrPut(index) {
                        RadialGradient(0f, 0f, 1f,
                            intArrayOf(commands.getInt(at + 44), commands.getInt(at + 48)),
                            floatArrayOf(commands.getFloat(at + 52), commands.getFloat(at + 56)), Shader.TileMode.CLAMP).apply {
                            setLocalMatrix(Matrix().apply {
                                setScale((right - left) * 0.70710677f, (bottom - top) * 0.70710677f)
                                postTranslate((left + right) / 2, (top + bottom) / 2)
                            })
                        }
                    }
                    if (commands.getInt(at + 60) and 1 != 0) {
                        clip.reset()
                        clip.addCircle((left + right) / 2, (top + bottom) / 2,
                            minOf(right - left, bottom - top) / 2, Path.Direction.CW)
                        val saved = canvas.save()
                        canvas.clipPath(clip)
                        canvas.drawRect(left, top, right, bottom, paint)
                        canvas.restoreToCount(saved)
                    } else canvas.drawRect(left, top, right, bottom, paint)
                    paint.shader = null
                } else {
                    val fill = commands.getInt(at + 44)
                    val radius = commands.getFloat(at + 56)
                    paint.style = Paint.Style.FILL
                    if (fill ushr 24 != 0) {
                        paint.color = fill
                        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
                    }
                    val stroke = commands.getFloat(at + 52)
                    val border = commands.getInt(at + 48)
                    if (stroke > 0 && border ushr 24 != 0) {
                        val edge = commands.getInt(at + 60) and 0xF00
                        paint.color = border
                        if (edge != 0) {
                            val r = minOf(radius, (right - left) / 2, (bottom - top) / 2)
                            val bt = if (edge == 0x100) stroke else 0f
                            val br = if (edge == 0x200) stroke else 0f
                            val bb = if (edge == 0x400) stroke else 0f
                            val bl = if (edge == 0x800) stroke else 0f
                            clip.reset()
                            clip.fillType = Path.FillType.EVEN_ODD
                            clip.addRoundRect(left, top, right, bottom, r, r, Path.Direction.CW)
                            if (left + bl < right - br && top + bt < bottom - bb) {
                                borderRadii[0] = (r - bl).coerceAtLeast(0f)
                                borderRadii[1] = (r - bt).coerceAtLeast(0f)
                                borderRadii[2] = (r - br).coerceAtLeast(0f)
                                borderRadii[3] = borderRadii[1]
                                borderRadii[4] = borderRadii[2]
                                borderRadii[5] = (r - bb).coerceAtLeast(0f)
                                borderRadii[6] = borderRadii[0]
                                borderRadii[7] = borderRadii[5]
                                clip.addRoundRect(left + bl, top + bt, right - br, bottom - bb, borderRadii, Path.Direction.CW)
                            }
                            paint.style = Paint.Style.FILL
                            canvas.drawPath(clip, paint)
                        } else {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = stroke
                            val inset = stroke / 2
                            val strokeRadius = (radius - inset).coerceAtLeast(0f)
                            canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, strokeRadius, strokeRadius, paint)
                        }
                        paint.style = Paint.Style.FILL
                    }
                }
            }
        } finally { canvas.restoreToCount(checkpoint) }
    }
}

internal class NativePlatformFont(val paint: TextPaint, val state: State<Any>, val typeface: Typeface) {
    val metrics: Paint.FontMetrics = paint.fontMetrics
    val punctuation: HanPunctuationFont by lazy(LazyThreadSafetyMode.NONE) { HanPunctuationFont(paint) }
}
