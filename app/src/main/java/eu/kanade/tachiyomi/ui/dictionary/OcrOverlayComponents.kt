package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrNormalizedBounds
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.orderedLineIndices

data class OcrSelection(
    val block: OcrTextBlock,
    val lookupString: String,
    val sentence: String,
    val sentenceOffset: Int,
    val anchorX: Float,
    val anchorY: Float,
    val anchorWidth: Float,
    val anchorHeight: Float,
)

private val borderColor = Color(0, 170, 255, 180)

@Composable
fun OcrBlockCanvas(
    blocks: List<OcrTextBlock>,
    boxScaleX: Float,
    boxScaleY: Float,
    activeBlock: OcrTextBlock?,
    activeMatchCount: Int,
    activeMatchOffset: Int,
    selection: OcrSelection?,
    onBlockTapped: (OcrTextBlock, Float, Float) -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier,
    showBoxes: Boolean = true,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val currentOnBlockTapped by rememberUpdatedState(onBlockTapped)
    val currentOnEmptyTap by rememberUpdatedState(onEmptyTap)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(blocks, boxScaleX, boxScaleY) {
                detectTapGestures { offset ->
                    val x = offset.x / size.width
                    val y = offset.y / size.height
                    val tapped = blocks.mapNotNull { block ->
                        block.screenLookupLineHit(x, y, size.width.toFloat(), size.height.toFloat())
                            ?.let { block to it.distance }
                    }.minByOrNull { it.second }?.first ?: OcrHitTester.findBlock(blocks, x, y, boxScaleX, boxScaleY) {
                        OcrNormalizedBounds(it.xmin, it.ymin, it.xmax, it.ymax)
                    }
                    if (tapped == null) {
                        currentOnEmptyTap()
                    } else {
                        val tapX = (offset.x / size.width).coerceIn(0f, 1f)
                        val tapY = (offset.y / size.height).coerceIn(0f, 1f)
                        currentOnBlockTapped(tapped, tapX, tapY)
                    }
                }
            },
    ) {
        blocks.forEach { block ->
            val isActive = block == activeBlock
            val geometries = block.lineGeometries

            if (geometries != null && geometries.size == block.lines.size) {
                geometries.forEachIndexed { geoIndex, geo ->
                    rotate(
                        geo.rotation,
                        Offset((geo.xmin + geo.xmax) / 2f * size.width, (geo.ymin + geo.ymax) / 2f * size.height),
                    ) {
                        if (showBoxes) drawBlockLine(geo, isActive, boxScaleX, boxScaleY)
                        if (isActive && activeMatchCount > 0 && selection != null) {
                            drawMatchHighlight(
                                block = block,
                                geo = geo,
                                geoIndex = geoIndex,
                                activeMatchCount = activeMatchCount,
                                activeMatchOffset = activeMatchOffset,
                                selection = selection,
                                boxScaleX = boxScaleX,
                                boxScaleY = boxScaleY,
                                highlightColor = highlightColor,
                            )
                        }
                    }
                }
            } else {
                if (showBoxes) drawBlockRect(block, isActive)
            }
        }
    }
}

private fun DrawScope.drawBlockLine(
    geo: eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry,
    isActive: Boolean,
    boxScaleX: Float,
    boxScaleY: Float,
) {
    val centerX = (geo.xmin + geo.xmax) / 2f
    val centerY = (geo.ymin + geo.ymax) / 2f
    val tightW = (geo.xmax - geo.xmin).coerceAtLeast(0.001f)
    val tightH = (geo.ymax - geo.ymin).coerceAtLeast(0.001f)

    val scaledW = tightW * boxScaleX
    val scaledH = tightH * boxScaleY
    val left = (centerX - scaledW / 2f) * size.width
    val top = (centerY - scaledH / 2f) * size.height
    val w = scaledW * size.width
    val h = scaledH * size.height

    drawRect(
        color = if (isActive) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f),
        topLeft = Offset(left, top),
        size = Size(w, h),
    )
    drawRect(
        color = borderColor,
        topLeft = Offset(left, top),
        size = Size(w, h),
        style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
    )
}

private fun DrawScope.drawBlockRect(
    block: OcrTextBlock,
    isActive: Boolean,
) {
    val blockLeft = block.xmin * size.width
    val blockTop = block.ymin * size.height
    val blockSize = Size(
        width = (block.xmax - block.xmin) * size.width,
        height = (block.ymax - block.ymin) * size.height,
    )
    drawRect(
        color = if (isActive) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f),
        topLeft = Offset(blockLeft, blockTop),
        size = blockSize,
    )
    drawRect(
        color = borderColor,
        topLeft = Offset(blockLeft, blockTop),
        size = blockSize,
        style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
    )
}

private fun DrawScope.drawMatchHighlight(
    block: OcrTextBlock,
    geo: eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry,
    geoIndex: Int,
    activeMatchCount: Int,
    activeMatchOffset: Int,
    selection: OcrSelection,
    boxScaleX: Float,
    boxScaleY: Float,
    highlightColor: Color,
) {
    val orderedIndices = block.orderedLineIndices()
    val orderedSentence = orderedIndices.joinToString("") { block.lines[it] }
    val lineOrder = if (selection.sentence == orderedSentence) orderedIndices else block.lines.indices.toList()

    var accumulated = 0
    for (i in lineOrder) {
        val lineLen = block.lines[i].length
        val lineEnd = accumulated + lineLen
        val absStart = selection.sentenceOffset + activeMatchOffset
        val absEnd = absStart + activeMatchCount
        if (absStart < lineEnd && absEnd > accumulated && i == geoIndex) {
            val overlapL = maxOf(absStart, accumulated)
            val overlapR = minOf(absEnd, lineEnd)
            val startFrac = (overlapL - accumulated).toFloat() / lineLen.coerceAtLeast(1)
            val endFrac = (overlapR - accumulated).toFloat() / lineLen.coerceAtLeast(1)

            val lCx = (geo.xmin + geo.xmax) / 2f
            val lCy = (geo.ymin + geo.ymax) / 2f
            val lTw = (geo.xmax - geo.xmin).coerceAtLeast(0.001f)
            val lTh = (geo.ymax - geo.ymin).coerceAtLeast(0.001f)
            val lSw = lTw * boxScaleX
            val lSh = lTh * boxScaleY
            val lLeft = (lCx - lSw / 2f) * size.width
            val lTop = (lCy - lSh / 2f) * size.height
            val lW = lSw * size.width
            val lH = lSh * size.height

            val origW = lW / boxScaleX
            val origH = lH / boxScaleY
            val padX = (lW - origW) / 2f
            val padY = (lH - origH) / 2f

            if (OcrHitTester.isLineVertical(
                    block.vertical,
                    geo.xmin * size.width,
                    geo.ymin * size.height,
                    geo.xmax * size.width,
                    geo.ymax * size.height,
                )
            ) {
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(lLeft, lTop + padY + origH * startFrac),
                    size = Size(lW, origH * (endFrac - startFrac)),
                )
            } else {
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(lLeft + padX + origW * startFrac, lTop),
                    size = Size(origW * (endFrac - startFrac), lH),
                )
            }
            return
        }
        accumulated = lineEnd
    }
}

@Composable
fun OcrStatusOverlay(
    isLoading: Boolean,
    error: String?,
    loadingText: String,
    modifier: Modifier = Modifier,
) {
    if (!isLoading && error == null) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(loadingText)
            } else {
                Text(error.orEmpty())
            }
        }
    }
}

@Composable
fun OcrTapHint(
    visible: Boolean,
    hintText: String,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = hintText,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

fun cropBitmap(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
    val w = bitmap.width; val h = bitmap.height
    val x = (left * w).toInt().coerceIn(0, w)
    val y = (top * h).toInt().coerceIn(0, h)
    val r = (right * w).toInt().coerceIn(x + 1, w)
    val b = (bottom * h).toInt().coerceIn(y + 1, h)
    return Bitmap.createBitmap(bitmap, x, y, r - x, b - y)
}

fun cropAroundAnchor(
    bitmap: Bitmap,
    anchorX: Float, anchorY: Float,
    anchorWidth: Float, anchorHeight: Float,
    aspectX: Int, aspectY: Int,
    paddingFactor: Float = 1.5f,
): Bitmap {
    val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
    val cx = ((anchorX + anchorWidth / 2f) / bw).coerceIn(0f, 1f)
    val cy = ((anchorY + anchorHeight / 2f) / bh).coerceIn(0f, 1f)
    val textW = (anchorWidth / bw * paddingFactor).coerceAtLeast(0.01f)
    val textH = (anchorHeight / bh * paddingFactor).coerceAtLeast(0.01f)
    // Presets are pixel-space ratios; convert to normalized space so the final
    // PIXEL dimensions match aspectX:aspectY regardless of bitmap orientation.
    val pixelRatio = if (aspectY > 0) aspectX.toFloat() / aspectY.toFloat() else 1f
    val normRatio = pixelRatio * bh / bw
    var cropW: Float; var cropH: Float
    if (textW / textH > normRatio) {
        cropH = textH; cropW = textH * normRatio
    } else {
        cropW = textW; cropH = textW / normRatio
    }
    // Sensible size bounds while preserving ratio
    val minSize = 0.20f
    val maxSize = 0.80f
    if (cropW < minSize || cropH < minSize) {
        val scale = minSize / minOf(cropW, cropH).coerceAtLeast(0.001f)
        cropW *= scale; cropH *= scale
    }
    if (cropW > maxSize || cropH > maxSize) {
        val scale = maxSize / maxOf(cropW, cropH)
        cropW *= scale; cropH *= scale
    }
    // Edge clamp preserving ratio (joint, not independent)
    val maxHalfW = minOf(cx, 1f - cx)
    val maxHalfH = minOf(cy, 1f - cy)
    var halfW = cropW / 2f
    var halfH = cropH / 2f
    if (halfW > maxHalfW) {
        halfW = maxHalfW
        halfH = halfW / normRatio
    }
    if (halfH > maxHalfH) {
        halfH = maxHalfH
        halfW = halfH * normRatio
    }
    halfW = halfW.coerceAtMost(maxHalfW)
    halfH = halfH.coerceAtMost(maxHalfH)
    val left = (cx - halfW).coerceAtLeast(0f)
    val top = (cy - halfH).coerceAtLeast(0f)
    val right = (cx + halfW).coerceAtMost(1f)
    val bottom = (cy + halfH).coerceAtMost(1f)
    return cropBitmap(bitmap, left, top, right, bottom)
}
