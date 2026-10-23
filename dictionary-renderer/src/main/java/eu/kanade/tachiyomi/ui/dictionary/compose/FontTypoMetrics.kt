package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.fonts.Font
import androidx.annotation.RequiresApi
import java.io.RandomAccessFile
import java.nio.ByteOrder

/** OS/2 sTypoAscender:sTypoDescender, normalized to one em like Blink's simple_font_data.cc. */
internal data class FontTypoRatio(val ascent: Float, val descent: Float)

@RequiresApi(29)
internal object FontTypoMetrics {
    private data class Typo(val ascender: Int, val descender: Int)

    fun ratio(font: Font): FontTypoRatio? {
        val typo = runCatching { fromFile(font) }.getOrNull()
            ?: runCatching { fromBuffer(font) }.getOrNull() ?: return null
        val ascent = typo.ascender.toFloat()
        val descent = -typo.descender.toFloat()
        val height = ascent + descent
        if (height <= 0f || ascent < 0f || ascent > height) return null
        return FontTypoRatio(ascent / height, descent / height)
    }

    private fun fromFile(font: Font): Typo? {
        val file = font.file ?: return null
        if (!file.isFile) return null
        return RandomAccessFile(file, "r").use { input ->
            val face = if (input.readInt() == TTC_TAG) {
                input.seek(8)
                val count = input.readInt()
                val index = font.ttcIndex
                if (index !in 0 until count) return@use null
                input.seek(12L + index * 4L)
                input.readInt().toLong() and 0xffffffffL
            } else {
                0L
            }
            input.seek(face + 4)
            val count = input.readUnsignedShort()
            input.seek(face + 12)
            val records = ByteArray(count * 16)
            input.readFully(records)
            val offset = findTable(records, count) ?: return@use null
            input.seek(offset.toLong() + 68)
            Typo(input.readShort().toInt(), input.readShort().toInt())
        }
    }

    private fun fromBuffer(font: Font): Typo? {
        val buffer = font.buffer?.duplicate()?.order(ByteOrder.BIG_ENDIAN) ?: return null
        val face = if (buffer.getInt(0) == TTC_TAG) {
            val count = buffer.getInt(8)
            val index = font.ttcIndex
            if (index !in 0 until count) return null
            buffer.getInt(12 + index * 4)
        } else {
            0
        }
        val count = buffer.getShort(face + 4).toInt() and 0xffff
        val records = ByteArray(count * 16)
        for (index in records.indices) records[index] = buffer.get(face + 12 + index)
        val offset = findTable(records, count) ?: return null
        if (offset + 72 > buffer.limit()) return null
        return Typo(buffer.getShort(offset + 68).toInt(), buffer.getShort(offset + 70).toInt())
    }

    private fun findTable(records: ByteArray, count: Int): Int? {
        for (index in 0 until count) {
            val start = index * 16
            if (records[start] == 'O'.code.toByte() && records[start + 1] == 'S'.code.toByte() &&
                records[start + 2] == '/'.code.toByte() && records[start + 3] == '2'.code.toByte()) {
                return ((records[start + 8].toInt() and 0xff) shl 24) or
                    ((records[start + 9].toInt() and 0xff) shl 16) or
                    ((records[start + 10].toInt() and 0xff) shl 8) or
                    (records[start + 11].toInt() and 0xff)
            }
        }
        return null
    }

    private const val TTC_TAG = 0x74746366
}
