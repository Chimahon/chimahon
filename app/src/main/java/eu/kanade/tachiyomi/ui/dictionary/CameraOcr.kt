package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import java.io.File
import kotlin.math.min

internal const val CAMERA_OCR_CAPTURE_NAME = "camera_ocr_capture.jpg"

/**
 * Longest-edge cap for the decoded capture. Deliberately well below
 * `chimahon.ocr.ImageChunking.MAX_TOTAL_PIXELS` so a still is never split into chunks.
 */
internal const val CAMERA_OCR_MAX_DIMENSION = 1600

internal fun createCaptureFile(context: Context): File = File(context.cacheDir, CAMERA_OCR_CAPTURE_NAME)

internal fun captureUri(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}

/**
 * Power-of-two subsample factor that brings [srcWidth] x [srcHeight] down towards
 * [maxDimension] on its longest edge. A final exact scale pass finishes the job,
 * so it is fine for this to overshoot.
 */
internal fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, maxDimension: Int): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || maxDimension <= 0) return 1
    val longestEdge = maxOf(srcWidth, srcHeight)
    if (longestEdge <= maxDimension) return 1
    var sampleSize = 1
    while (longestEdge / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

/** Exact post-subsample scale factor, 1f when the image already fits. */
internal fun scaleDownFactor(width: Int, height: Int, maxDimension: Int): Float {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1f
    val longestEdge = maxOf(width, height)
    if (longestEdge <= maxDimension) return 1f
    return maxDimension.toFloat() / longestEdge
}

/**
 * Maps a profile language code onto an [OcrLanguage]. Falls back to a base-language match
 * so region-tagged codes such as `ja-JP` still resolve, then to Japanese.
 */
internal fun resolveOcrLanguage(profileLanguageCode: String): OcrLanguage {
    if (profileLanguageCode.isBlank()) return OcrLanguage.JAPANESE
    OcrLanguage.entries.firstOrNull { it.bcp47.equals(profileLanguageCode, ignoreCase = true) }
        ?.let { return it }
    OcrLanguage.entries.firstOrNull {
        profileLanguageCode.startsWith("${it.bcp47}-", ignoreCase = true)
    }?.let { return it }
    return OcrLanguage.JAPANESE
}

internal fun exifRotationDegrees(file: File): Int {
    return runCatching {
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

/**
 * Decodes a camera capture downscaled to [maxDimension] on its longest edge and rotated
 * upright per EXIF. Returns null when the file is missing, empty or undecodable, which is
 * how camera apps that ignore the output Uri surface here.
 */
internal fun decodeCapture(file: File, maxDimension: Int = CAMERA_OCR_MAX_DIMENSION): Bitmap? {
    if (!file.isFile || file.length() == 0L) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

    val factor = scaleDownFactor(decoded.width, decoded.height, maxDimension)
    val scaled = if (factor < 1f) {
        val width = (decoded.width * factor).toInt().coerceAtLeast(1)
        val height = (decoded.height * factor).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decoded, width, height, true)
    } else {
        decoded
    }
    if (scaled !== decoded) decoded.recycle()

    return applyExifRotation(scaled, exifRotationDegrees(file))
}

private fun applyExifRotation(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/** Where a `ContentScale.Fit` image lands inside its canvas, in canvas pixels. */
internal data class FittedImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal fun fitImageRect(
    imgWidth: Int,
    imgHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
): FittedImageRect {
    if (imgWidth <= 0 || imgHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
        return FittedImageRect(0f, 0f, maxOf(canvasWidth, 0f), maxOf(canvasHeight, 0f))
    }
    val scale = min(canvasWidth / imgWidth, canvasHeight / imgHeight)
    val width = imgWidth * scale
    val height = imgHeight * scale
    return FittedImageRect(
        left = (canvasWidth - width) / 2f,
        top = (canvasHeight - height) / 2f,
        width = width,
        height = height,
    )
}

/**
 * Re-normalizes image-relative OCR blocks into canvas-relative ones so they line up with a
 * `ContentScale.Fit` image. Drops blocks that collapse to zero area.
 */
internal fun remapBlocksToCanvas(
    blocks: List<OcrTextBlock>,
    rect: FittedImageRect,
    canvasWidth: Float,
    canvasHeight: Float,
): List<OcrTextBlock> {
    if (canvasWidth <= 0f || canvasHeight <= 0f) return blocks
    val toCanvasX = { x: Float -> (rect.left + x * rect.width) / canvasWidth }
    val toCanvasY = { y: Float -> (rect.top + y * rect.height) / canvasHeight }
    return blocks.mapNotNull { block ->
        val xmin = toCanvasX(block.xmin)
        val ymin = toCanvasY(block.ymin)
        val xmax = toCanvasX(block.xmax)
        val ymax = toCanvasY(block.ymax)
        if (xmax <= xmin || ymax <= ymin) return@mapNotNull null
        block.copy(
            xmin = xmin,
            ymin = ymin,
            xmax = xmax,
            ymax = ymax,
            lineGeometries = block.lineGeometries?.map { geo ->
                OcrLineGeometry(
                    xmin = toCanvasX(geo.xmin),
                    ymin = toCanvasY(geo.ymin),
                    xmax = toCanvasX(geo.xmax),
                    ymax = toCanvasY(geo.ymax),
                    rotation = geo.rotation,
                )
            },
        )
    }
}
