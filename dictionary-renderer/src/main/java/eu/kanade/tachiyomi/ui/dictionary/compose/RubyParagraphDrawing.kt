package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Canvas
import android.graphics.fonts.Font
import android.graphics.text.PositionedGlyphs
import android.os.Build
import android.text.TextPaint
import androidx.annotation.RequiresApi

/** Final glyph buffers use paragraph coordinates, shared across words with the same font and paint. */
public class RubyParagraphDrawing internal constructor(private val commands: List<RubyDrawCommand>) {
    fun draw(canvas: Canvas, x: Float, y: Float) {
        if (commands.isEmpty()) return
        val checkpoint = canvas.save()
        try {
            canvas.translate(x, y)
            commands.forEach { it.draw(canvas) }
        } finally { canvas.restoreToCount(checkpoint) }
    }

    companion object {
        fun column(metrics: RubyMetrics): RubyParagraphDrawing {
            val builder = RubyGlyphBatchBuilder()
            appendColumn(builder, metrics, 0f, 0f)
            return RubyParagraphDrawing(builder.build())
        }

        public fun appendColumn(builder: RubyGlyphBatchBuilder, metrics: RubyMetrics, x: Float, y: Float) {
            metrics.readingLayout.appendTo(builder,
                x + (metrics.widthPx - metrics.readingLayout.advancePx) / 2f, y + metrics.readingOffsetPx)
            metrics.baseLayout.appendTo(builder,
                x + (metrics.widthPx - metrics.baseLayout.advancePx) / 2f, y + metrics.baseOffsetPx)
        }
    }
}

public interface RubyDrawCommand {
    fun draw(canvas: Canvas)
}

public class RubyGlyphBatchBuilder {
    private data class FontPaint(val font: Font, val paint: TextPaint)
    private class Placement(val glyphs: PositionedGlyphs, val start: Int, val end: Int, val x: Float, val y: Float)
    private val glyphs = LinkedHashMap<FontPaint, MutableList<Placement>>()
    private val text = ArrayList<RubyDrawCommand>()

    @RequiresApi(31)
    fun glyphs(font: Font, value: PositionedGlyphs, start: Int, end: Int, x: Float, baseline: Float, paint: TextPaint) {
        glyphs.getOrPut(FontPaint(font, paint)) { ArrayList() }.add(Placement(value, start, end, x, baseline))
    }

    fun text(value: String, start: Int, end: Int, rtl: Boolean, x: Float, baseline: Float, paint: TextPaint) {
        text.add(object : RubyDrawCommand {
            override fun draw(canvas: Canvas) = canvas.drawTextRun(value, start, end,
                0, value.length, x, baseline, rtl, paint)
        })
    }

    fun build(): List<RubyDrawCommand> = buildList {
        if (Build.VERSION.SDK_INT >= 31) appendGlyphs(this)
        addAll(text)
    }

    @RequiresApi(31)
    private fun appendGlyphs(commands: MutableList<RubyDrawCommand>) {
        for ((key, placements) in glyphs) {
            val ids = IntArray(placements.sumOf { it.end - it.start })
            val positions = FloatArray(ids.size * 2)
            var offset = 0
            for (placement in placements) {
                for (index in placement.start until placement.end) {
                    ids[offset] = placement.glyphs.getGlyphId(index)
                    positions[offset * 2] = placement.glyphs.getGlyphX(index) + placement.x
                    positions[offset * 2 + 1] = placement.glyphs.getGlyphY(index) + placement.y
                    offset++
                }
            }
            commands.add(RubyGlyphBatch(key.font, key.paint, ids, positions))
        }
    }
}

@RequiresApi(31)
private class RubyGlyphBatch(private val font: Font, private val paint: TextPaint,
    private val ids: IntArray, private val positions: FloatArray) : RubyDrawCommand {
    override fun draw(canvas: Canvas) = canvas.drawGlyphs(ids, 0, positions, 0, ids.size, font, paint)
}
