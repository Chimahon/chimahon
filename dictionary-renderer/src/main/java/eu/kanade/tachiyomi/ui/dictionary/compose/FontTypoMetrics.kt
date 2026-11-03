package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.fonts.Font
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** OS/2 sTypoAscender:sTypoDescender, normalized to one em like Blink's simple_font_data.cc. */
internal data class FontTypoRatio(val ascent: Float, val descent: Float)

@RequiresApi(29)
internal object FontTypoMetrics {
    fun ratio(font: Font): FontTypoRatio? = fontTypoRatio(font.buffer, font.ttcIndex)
}

/** Font.getBuffer supplies the font file already owned by Android; never reopen it. */
internal fun fontTypoRatio(source: ByteBuffer, ttcIndex: Int): FontTypoRatio? {
    val buffer = source.duplicate().order(ByteOrder.BIG_ENDIAN)
    val limit = buffer.limit()
    if (limit < 12) return null
    val face = if (buffer.getInt(0) == 0x74746366) {
        val count = buffer.getInt(8)
        if (count < 0 || count > (limit - 12) / 4 || ttcIndex !in 0 until count) return null
        buffer.getInt(12 + ttcIndex * 4)
    } else 0
    if (face < 0 || face > limit - 12) return null
    val tables = buffer.getShort(face + 4).toInt() and 0xffff
    if (tables > (limit - face - 12) / 16) return null
    for (index in 0 until tables) {
        val record = face + 12 + index * 16
        if (buffer.getInt(record) != 0x4f532f32) continue
        // SFNT table offsets are relative to the file, including in a TTC face.
        val offset = buffer.getInt(record + 8)
        val length = buffer.getInt(record + 12)
        if (offset < 0 || length < 72 || offset > limit - 72 || length > limit - offset) return null
        val ascent = buffer.getShort(offset + 68).toInt()
        val descent = -buffer.getShort(offset + 70).toInt()
        val height = ascent + descent
        if (height <= 0 || ascent < 0 || ascent > height) return null
        return FontTypoRatio(ascent.toFloat() / height, descent.toFloat() / height)
    }
    return null
}
