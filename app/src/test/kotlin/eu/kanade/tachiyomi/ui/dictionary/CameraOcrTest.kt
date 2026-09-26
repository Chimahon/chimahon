package eu.kanade.tachiyomi.ui.dictionary

import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraOcrTest {

    @Test
    fun `resolveOcrLanguage matches a profile code exactly`() {
        assertEquals(OcrLanguage.ENGLISH, resolveOcrLanguage("en"))
        assertEquals(OcrLanguage.KOREAN, resolveOcrLanguage("ko"))
        assertEquals(OcrLanguage.PORTUGUESE, resolveOcrLanguage("pt-BR"))
    }

    @Test
    fun `resolveOcrLanguage ignores case`() {
        assertEquals(OcrLanguage.JAPANESE, resolveOcrLanguage("JA"))
        assertEquals(OcrLanguage.ENGLISH, resolveOcrLanguage("En"))
    }

    @Test
    fun `resolveOcrLanguage falls back to the base language of a region tag`() {
        assertEquals(OcrLanguage.JAPANESE, resolveOcrLanguage("ja-JP"))
        assertEquals(OcrLanguage.SPANISH, resolveOcrLanguage("es-419"))
        assertEquals(OcrLanguage.CANTONESE, resolveOcrLanguage("yue-Hant-HK"))
    }

    @Test
    fun `resolveOcrLanguage defaults to Japanese`() {
        assertEquals(OcrLanguage.JAPANESE, resolveOcrLanguage(""))
        assertEquals(OcrLanguage.JAPANESE, resolveOcrLanguage("   "))
        assertEquals(OcrLanguage.JAPANESE, resolveOcrLanguage("klingon"))
    }

    @Test
    fun `calculateInSampleSize leaves small images untouched`() {
        assertEquals(1, calculateInSampleSize(800, 600, 1600))
        assertEquals(1, calculateInSampleSize(1600, 1600, 1600))
    }

    @Test
    fun `calculateInSampleSize steps down powers of two`() {
        assertEquals(2, calculateInSampleSize(4000, 3000, 1600))
        assertEquals(4, calculateInSampleSize(12000, 9000, 1600))
    }

    @Test
    fun `calculateInSampleSize is tolerant of invalid input`() {
        assertEquals(1, calculateInSampleSize(0, 0, 1600))
        assertEquals(1, calculateInSampleSize(4000, 3000, 0))
    }

    @Test
    fun `scaleDownFactor brings the longest edge to the cap`() {
        assertEquals(1f, scaleDownFactor(1200, 900, 1600))

        val factor = scaleDownFactor(4000, 3000, 1600)
        assertEquals(0.4f, factor, 0.0001f)
        assertTrue(4000 * factor <= 1600f)
    }

    @Test
    fun `scaleDownFactor uses the longest edge, not the shortest`() {
        val portrait = scaleDownFactor(1000, 4000, 1600)
        assertEquals(0.4f, portrait, 0.0001f)
    }

    @Test
    fun `fitImageRect letterboxes a wide image`() {
        // 16:9 photo inside a square canvas -> bars top and bottom.
        val rect = fitImageRect(1600, 900, 1000f, 1000f)

        assertEquals(0f, rect.left, 0.001f)
        assertEquals(218.75f, rect.top, 0.001f)
        assertEquals(1000f, rect.width, 0.001f)
        assertEquals(562.5f, rect.height, 0.001f)
    }

    @Test
    fun `fitImageRect pillarboxes a tall image`() {
        // 1:2 photo inside a square canvas -> bars left and right.
        val rect = fitImageRect(500, 1000, 800f, 800f)

        assertEquals(200f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(400f, rect.width, 0.001f)
        assertEquals(800f, rect.height, 0.001f)
    }

    @Test
    fun `fitImageRect is tolerant of invalid input`() {
        val rect = fitImageRect(0, 0, 100f, 200f)

        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(100f, rect.width, 0.001f)
        assertEquals(200f, rect.height, 0.001f)
    }

    @Test
    fun `remapBlocksToCanvas shifts blocks out of the letterbox bars`() {
        val rect = fitImageRect(1600, 900, 1000f, 1000f)
        val block = OcrTextBlock(
            xmin = 0f,
            ymin = 0f,
            xmax = 1f,
            ymax = 1f,
            lines = listOf("full frame"),
        )

        val remapped = remapBlocksToCanvas(listOf(block), rect, 1000f, 1000f).single()

        // The image spans y 218.75..781.25, so the full-frame block shrinks to that band.
        assertEquals(0f, remapped.xmin, 0.001f)
        assertEquals(0.21875f, remapped.ymin, 0.001f)
        assertEquals(1f, remapped.xmax, 0.001f)
        assertEquals(0.78125f, remapped.ymax, 0.001f)
    }

    @Test
    fun `remapBlocksToCanvas remaps line geometries alongside the block`() {
        val rect = fitImageRect(500, 1000, 800f, 800f)
        val block = OcrTextBlock(
            xmin = 0.25f,
            ymin = 0.5f,
            xmax = 0.75f,
            ymax = 1f,
            lines = listOf("a", "b"),
            vertical = true,
            lineGeometries = listOf(
                OcrLineGeometry(0.25f, 0.5f, 0.75f, 0.75f),
                OcrLineGeometry(0.25f, 0.75f, 0.75f, 1f),
            ),
        )

        val remapped = remapBlocksToCanvas(listOf(block), rect, 800f, 800f).single()

        // Image spans x 200..600 of an 800px canvas, so 0.25 becomes (200 + 100) / 800 = 0.375.
        assertEquals(0.375f, remapped.xmin, 0.001f)
        assertEquals(0.5f, remapped.ymin, 0.001f)
        assertEquals(0.625f, remapped.xmax, 0.001f)
        assertEquals(1f, remapped.ymax, 0.001f)
        assertEquals(2, remapped.lineGeometries?.size)
        assertEquals(0.375f, remapped.lineGeometries?.get(0)?.xmin ?: 0f, 0.001f)
    }

    @Test
    fun `remapBlocksToCanvas drops blocks that collapse`() {
        val rect = fitImageRect(0, 0, 100f, 200f)
        val degenerate = OcrTextBlock(0.5f, 0.5f, 0.5f, 0.5f, lines = listOf("dot"))

        val remapped = remapBlocksToCanvas(listOf(degenerate), rect, 100f, 200f)

        assertTrue(remapped.isEmpty())
    }

    @Test
    fun `remapBlocksToCanvas passes blocks through on a degenerate canvas`() {
        val rect = fitImageRect(1600, 900, 0f, 0f)
        val block = OcrTextBlock(0f, 0f, 1f, 1f, lines = listOf("unchanged"))

        val remapped = remapBlocksToCanvas(listOf(block), rect, 0f, 0f)

        assertEquals(listOf(block), remapped)
    }
}
