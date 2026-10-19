package eu.kanade.tachiyomi.ui.dictionary

import chimahon.ocr.OcrCharacterLine
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrResult
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.uniformCharOffset

internal fun List<OcrResult>.toScreenLookupBlocks(language: String): List<OcrTextBlock> {
    return mapNotNull { result ->
        val bbox = result.tightBoundingBox
        val xmin = bbox.x.toFloat().coerceIn(0f, 1f)
        val ymin = bbox.y.toFloat().coerceIn(0f, 1f)
        val xmax = (bbox.x + bbox.width).toFloat().coerceIn(0f, 1f)
        val ymax = (bbox.y + bbox.height).toFloat().coerceIn(0f, 1f)
        val lines = result.text.split("\n")

        if (xmax <= xmin || ymax <= ymin || result.text.isBlank()) {
            null
        } else {
            OcrTextBlock(
                xmin = xmin,
                ymin = ymin,
                xmax = xmax,
                ymax = ymax,
                lines = lines,
                vertical = result.forcedOrientation == "vertical",
                lineGeometries = result.constituentBoxes?.map { lineBox ->
                    OcrLineGeometry(
                        xmin = lineBox.x.toFloat().coerceIn(0f, 1f),
                        ymin = lineBox.y.toFloat().coerceIn(0f, 1f),
                        xmax = (lineBox.x + lineBox.width).toFloat().coerceIn(0f, 1f),
                        ymax = (lineBox.y + lineBox.height).toFloat().coerceIn(0f, 1f),
                        rotation = (lineBox.rotation ?: 0.0).toFloat(),
                    )
                },
                language = language,
            )
        }
    }
}

internal fun OcrTextBlock.screenLookupLineHit(tapX: Float, tapY: Float, width: Float, height: Float) =
    lineGeometries?.takeIf { it.size == lines.size }?.let { geometries ->
        var offset = 0
        val characterLines = geometries.mapIndexed { index, geo ->
            OcrCharacterLine(
                value = index,
                text = lines[index],
                textOffset = offset,
                left = geo.xmin * width,
                top = geo.ymin * height,
                right = geo.xmax * width,
                bottom = geo.ymax * height,
                rotation = geo.rotation,
                vertical = OcrHitTester.isLineVertical(
                    vertical, geo.xmin * width, geo.ymin * height, geo.xmax * width, geo.ymax * height,
                ),
            ).also { offset += lines[index].length }
        }
        OcrHitTester.hitLines(characterLines, tapX * width, tapY * height)
    }

internal fun OcrTextBlock.screenLookupCharOffset(tapX: Float, tapY: Float, width: Float = 1f, height: Float = 1f): Int {
    if (fullText.isEmpty()) return 0
    screenLookupLineHit(tapX, tapY, width, height)?.let { return it.textOffset }
    val blockWidth = (xmax - xmin).coerceAtLeast(0.001f)
    val blockHeight = (ymax - ymin).coerceAtLeast(0.001f)
    val localX = (tapX - xmin).coerceIn(0f, blockWidth)
    val localY = (tapY - ymin).coerceIn(0f, blockHeight)
    return uniformCharOffset(this, localX * width, localY * height, blockWidth * width, blockHeight * height)
        .coerceIn(0, fullText.lastIndex.coerceAtLeast(0))
}
