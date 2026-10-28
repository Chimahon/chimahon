package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FontTypoMetricsTest {
    private fun font(face: Int = 0, tables: Int = 1): ByteBuffer = ByteBuffer.allocate(256).apply {
        putInt(face, 0x00010000)
        putShort(face + 4, tables.toShort())
        val record = face + 12
        putInt(record, 0x4f532f32)
        putInt(record + 8, 160)
        putInt(record + 12, 78)
        putShort(228, 750)
        putShort(230, -250)
    }

    @Test fun readsTheOs2TableWithoutChangingBufferState() {
        val source = font().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        val position = source.position()
        val order = source.order()
        assertEquals(FontTypoRatio(0.75f, 0.25f), fontTypoRatio(source, 0))
        assertEquals(position, source.position())
        assertEquals(order, source.order())
    }

    @Test fun readsAnAbsoluteTableOffsetInASelectedCollectionFace() {
        val source = font(face = 64).apply {
            putInt(0, 0x74746366)
            putInt(8, 2)
            putInt(12, 32)
            putInt(16, 64)
        }
        assertEquals(FontTypoRatio(0.75f, 0.25f), fontTypoRatio(source, 1))
        assertNull(fontTypoRatio(source, 2))
        assertNull(fontTypoRatio(source, -1))
    }

    @Test fun skipsUnrelatedTablesAndRejectsMissingOrInvalidMetrics() {
        val source = font(tables = 2)
        val record = ByteArray(16).also { source.duplicate().apply { position(12); get(it) } }
        source.position(28)
        source.put(record)
        source.putInt(12, 0x68656164)
        assertEquals(FontTypoRatio(0.75f, 0.25f), fontTypoRatio(source, 0))
        source.putShort(230, 250)
        assertNull(fontTypoRatio(source, 0))
        source.putInt(28, 0)
        assertNull(fontTypoRatio(source, 0))
    }

    @Test fun truncatedAndOverflowingDirectoriesAreRejectedWithoutExceptions() {
        for (size in 0 until 12) assertNull(fontTypoRatio(ByteBuffer.allocate(size), 0))
        assertNull(fontTypoRatio(font().apply { putShort(4, 32767) }, 0))
        assertNull(fontTypoRatio(font().apply { putInt(20, Int.MAX_VALUE) }, 0))
        assertNull(fontTypoRatio(font().apply { putInt(24, 71) }, 0))
        assertNull(fontTypoRatio(font().apply { putInt(24, Int.MAX_VALUE) }, 0))
    }
}
