package chimahon.novel.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import chimahon.ocr.OcrCharacterLine
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.ocr.OcrTextOverlayPainter
import chimahon.ocr.extractOcrLookupText
import chimahon.ocr.extractWholeWord
import chimahon.ocr.isOcrLookupStartChar
import chimahon.ocr.shouldScanWholeWord
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private enum class ImageViewerLoadState { Loading, Ready, Failed }

private data class NovelOcrBlock(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val vertical: Boolean,
    val lines: List<NovelOcrLine> = emptyList(),
)

private data class NovelOcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rotation: Float,
)

private data class NovelOcrLookup(
    val lookupText: String,
    val sentence: String,
    val sentenceOffset: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val vertical: Boolean,
)

private data class NovelOcrLineTarget(
    val block: NovelOcrBlock,
    val line: NovelOcrLine,
    val rect: RectF,
    val vertical: Boolean,
)

/**
 * Full-screen, zoomable image viewer for novel illustrations.
 *
 * Uses [SubsamplingScaleImageView] (SSIV) so even very large images are
 * decoded in tiles and don't blow up the heap. Standard raster formats
 * (JPEG/PNG/GIF/WebP) are decoded by Coil into a bitmap and then handed
 * to SSIV.
 *
 * Tap outside the image or press back to dismiss. Pan and
 * double-tap-to-zoom are handled by SSIV itself.
 */
@Composable
fun NovelImageViewer(
    imageUri: String,
    readerBackgroundColor: Int,
    ocrLanguage: OcrLanguage = OcrLanguage.JAPANESE,
    ocrScanResolution: String = "",
    recognizeImage: suspend (Bitmap, OcrLanguage) -> List<OcrResult> = { _, _ -> emptyList() },
    onOcrLookupRequested: (String, String, Int, Float, Float, Float, Float, Boolean, Bitmap) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    isPopupActive: Boolean = false,
    onDismissPopupRequested: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val backdropColor = remember(readerBackgroundColor) { imageViewerBackground(readerBackgroundColor) }
    val scope = rememberCoroutineScope()
    var loadState by remember(imageUri) { mutableStateOf(ImageViewerLoadState.Loading) }
    var imageView by remember(imageUri) { mutableStateOf<NovelImageView?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(IntOffset.Zero) }
    var ocrRunning by remember(imageUri) { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropColor),
        contentAlignment = Alignment.Center,
    ) {
        // Always-rendered SSIV host.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                NovelImageView(ctx).apply {
                    imageView = this
                    scanWholeWord = shouldScanWholeWord(ocrScanResolution, ocrLanguage.bcp47)
                    onViewClicked = onDismiss
                    onImageLongPressed = { x, y ->
                        menuPosition = IntOffset(x.roundToInt(), y.roundToInt())
                        menuExpanded = true
                    }
                    onOcrLookup = onOcrLookupRequested
                    this.isPopupActive = isPopupActive
                    this.onDismissPopup = onDismissPopupRequested
                    clipToPadding = false
                    clipChildren = false
                    onLoadComplete = { success ->
                        loadState = if (success) ImageViewerLoadState.Ready
                        else ImageViewerLoadState.Failed
                    }
                }
            },
            update = { view ->
                imageView = view
                view.onViewClicked = onDismiss
                view.onImageLongPressed = { x, y ->
                    menuPosition = IntOffset(x.roundToInt(), y.roundToInt())
                    menuExpanded = true
                }
                view.onOcrLookup = onOcrLookupRequested
                view.isPopupActive = isPopupActive
                view.onDismissPopup = onDismissPopupRequested
                view.onLoadComplete = { success ->
                    loadState = if (success) ImageViewerLoadState.Ready
                    else ImageViewerLoadState.Failed
                }
                view.loadImage(context, imageUri)
            },
        )

        when (loadState) {
            ImageViewerLoadState.Loading -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
            )
            ImageViewerLoadState.Failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Couldn't load image",
                    color = Color.White,
                )
            }
            ImageViewerLoadState.Ready -> { /* image is visible */ }
        }

        if (ocrRunning) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { menuPosition },
        ) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(if (ocrRunning) "Running OCR..." else "OCR") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    enabled = !ocrRunning,
                    onClick = {
                        menuExpanded = false
                        val view = imageView ?: return@DropdownMenuItem
                        val bitmap = view.loadedBitmap ?: return@DropdownMenuItem
                        scope.launch {
                            ocrRunning = true
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    recognizeImage(bitmap, ocrLanguage)
                                        .mapNotNull { it.toNovelOcrBlock() }
                                }
                            }
                            ocrRunning = false
                            result.onSuccess { blocks ->
                                view.setOcrBlocks(blocks)
                                Toast.makeText(
                                    context,
                                    if (blocks.isEmpty()) "No text found" else "OCR ready",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }.onFailure {
                                Toast.makeText(context, "OCR failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy image") },
                    leadingIcon = {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        val bitmap = imageView?.loadedBitmap ?: return@DropdownMenuItem
                        scope.launch {
                            val copied = withContext(Dispatchers.IO) {
                                runCatching { copyImageToClipboard(context, bitmap) }.isSuccess
                            }
                            Toast.makeText(
                                context,
                                if (copied) "Image copied" else "Couldn't copy image",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Save image") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                    },
                    onClick = {
                        menuExpanded = false
                        val bitmap = imageView?.loadedBitmap ?: return@DropdownMenuItem
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                runCatching { saveImage(context, bitmap) }.isSuccess
                            }
                            Toast.makeText(
                                context,
                                if (saved) "Image saved" else "Couldn't save image",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

/**
 * A thin wrapper around [SubsamplingScaleImageView] that:
 *  - Loads the image via Coil into a bitmap (works for file://, http(s)://,
 *    data:, content:).
 *  - Hands the bitmap to SSIV for zoomable, tiled display.
 *  - Dismisses the viewer when the black area outside the image is tapped.
 */
private class NovelImageView(context: Context) : FrameLayout(context) {

    private val ssiv: NovelSubsamplingImageView = NovelSubsamplingImageView(context).apply {
        setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
        setMinimumDpi(1)
        setDoubleTapZoomDuration(300)
        setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
        setZoomEnabled(true)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // `scale` is the current minimum (fit-to-screen) scale after
                    // the image is decoded. Configure zoom bounds relative to it.
                    val base = scale
                    if (base > 0f) {
                        maxScale = base * 5f
                        setDoubleTapZoomScale(base * 2f)
                    }
                    onLoadComplete?.invoke(true)
                }

                override fun onImageLoadError(error: Exception) {
                    onLoadComplete?.invoke(false)
                }
            },
        )
    }

    var onViewClicked: (() -> Unit)? = null
    var onLoadComplete: ((Boolean) -> Unit)? = null
    var onImageLongPressed: ((Float, Float) -> Unit)? = null
    var onOcrLookup: ((String, String, Int, Float, Float, Float, Float, Boolean, Bitmap) -> Unit)? = null
    var scanWholeWord: Boolean = false
        set(value) {
            field = value
            ssiv.scanWholeWord = value
        }
    var isPopupActive: Boolean = false
    var onDismissPopup: (() -> Unit)? = null

    var loadedBitmap: Bitmap? = null
        private set

    private var lastLoadedUri: String? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var tapCandidate = false
    private var longPressHandled = false
    private val longPressDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onLongPress(event: MotionEvent) {
                if (loadedBitmap == null || !ssiv.isReady || isOutsideImage(event.x, event.y)) return
                longPressHandled = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onImageLongPressed?.invoke(event.x, event.y)
            }
        },
    )

    init {
        addView(ssiv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                tapCandidate = true
                longPressHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - touchDownX) > touchSlop ||
                    kotlin.math.abs(event.y - touchDownY) > touchSlop
                ) {
                    tapCandidate = false
                }
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }

        longPressDetector.onTouchEvent(event)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && tapCandidate && !longPressHandled) {
            val lookup = ssiv.getOcrLookup(event.x, event.y)
            val bitmap = loadedBitmap
            if (lookup != null && bitmap != null) {
                onOcrLookup?.invoke(
                    lookup.lookupText,
                    lookup.sentence,
                    lookup.sentenceOffset,
                    lookup.x,
                    lookup.y,
                    lookup.width,
                    lookup.height,
                    lookup.vertical,
                    bitmap,
                )
            } else if (isPopupActive) {
                onDismissPopup?.invoke()
            } else if (isOutsideImage(event.x, event.y)) {
                onViewClicked?.invoke()
            }
        }
        return handled
    }

    fun setOcrBlocks(blocks: List<NovelOcrBlock>) {
        ssiv.setOcrBlocks(blocks)
    }

    private fun isOutsideImage(x: Float, y: Float): Boolean {
        if (!ssiv.isReady || ssiv.sWidth <= 0 || ssiv.sHeight <= 0) return false
        val sourcePoint = ssiv.viewToSourceCoord(x, y) ?: return false
        return sourcePoint.x < 0f || sourcePoint.x > ssiv.sWidth ||
            sourcePoint.y < 0f || sourcePoint.y > ssiv.sHeight
    }

    /**
     * Load [uri] into the SSIV via Coil. Skips if we've already loaded this URI.
     */
    fun loadImage(context: Context, uri: String) {
        if (uri == lastLoadedUri) return
        lastLoadedUri = uri

        val req = ImageRequest.Builder(context)
            .data(uri)
            .size(Size.ORIGINAL)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .target(
                onSuccess = { result ->
                    val drawable: Drawable = result.asDrawable(context.resources)
                    val src: Bitmap? = (drawable as? BitmapDrawable)?.bitmap
                    if (src != null) {
                        // SSIV reads pixels from the bitmap, so it can't use a
                        // hardware-backed bitmap. Copy to ARGB_8888 if needed.
                        val config = src.config?.takeIf { it != Bitmap.Config.HARDWARE }
                            ?: Bitmap.Config.ARGB_8888
                        val copy = try {
                            if (src.config == config) src else src.copy(config, false)
                        } catch (_: Throwable) {
                            src
                        }
                        loadedBitmap = copy
                        ssiv.setImage(ImageSource.bitmap(copy))
                    } else {
                        onLoadComplete?.invoke(false)
                    }
                },
                onError = { _ ->
                    onLoadComplete?.invoke(false)
                },
            )
            .build()
        context.imageLoader.enqueue(req)
    }
}

private class NovelSubsamplingImageView(context: Context) : SubsamplingScaleImageView(context) {
    private var ocrBlocks: List<NovelOcrBlock> = emptyList()
    var scanWholeWord: Boolean = false
    private val density = resources.displayMetrics.density
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(190, 255, 255, 255)
    }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = android.graphics.Color.argb(220, 0, 150, 210)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
    }

    fun setOcrBlocks(blocks: List<NovelOcrBlock>) {
        ocrBlocks = blocks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isReady || ocrBlocks.isEmpty()) return

        for (block in ocrBlocks) {
            val rect = blockViewRect(block) ?: continue
            if (rect.right < 0f || rect.bottom < 0f || rect.left > width || rect.top > height) continue

            if (block.lines.isEmpty()) {
                drawOcrText(
                    canvas,
                    block.displayText,
                    rect,
                    block.vertical,
                    rotation = 0f,
                )
            } else {
                block.lines.forEach { line ->
                    val lineRect = lineViewRect(line) ?: return@forEach
                    drawOcrText(
                        canvas = canvas,
                        text = line.text,
                        rect = lineRect,
                        vertical = line.isVertical(block.vertical),
                        rotation = line.rotation,
                    )
                }
            }
        }
    }

    private fun drawOcrText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        vertical: Boolean,
        rotation: Float,
    ) {
        if (text.isBlank() || rect.width() <= 0f || rect.height() <= 0f) return
        canvas.save()
        OcrTextOverlayPainter.drawLine(
            canvas = canvas,
            text = text,
            rect = rect,
            rotation = rotation,
            vertical = vertical,
            density = density,
            scaleCompensation = 1f,
            minimumTextSize = 1f,
            textPaint = textPaint,
            backgroundPaint = boxFill,
            borderPaint = boxStroke,
        )
        canvas.restore()
    }
    fun getOcrLookup(viewX: Float, viewY: Float): NovelOcrLookup? {
        if (!isReady || ocrBlocks.isEmpty()) return null
        val lines = ocrBlocks.flatMap { block ->
            if (block.lines.isEmpty()) {
                val rect = blockViewRect(block) ?: return@flatMap emptyList<OcrCharacterLine<NovelOcrLineTarget>>()
                val line = NovelOcrLine(block.displayText, block.left, block.top, block.right, block.bottom, 0f)
                val target = NovelOcrLineTarget(block, line, rect, block.vertical)
                listOf(
                    OcrCharacterLine(
                        value = target,
                        text = block.displayText,
                        textOffset = 0,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        rotation = 0f,
                        vertical = block.vertical,
                    ),
                )
            } else {
                block.lines.mapIndexedNotNull { lineIndex, line ->
                    val rect = lineViewRect(line) ?: return@mapIndexedNotNull null
                    val vertical = line.isVertical(block.vertical)
                    OcrCharacterLine(
                        value = NovelOcrLineTarget(block, line, rect, vertical),
                        text = line.text,
                        textOffset = block.lines.take(lineIndex).sumOf { it.text.length } +
                            if (!block.vertical) lineIndex else 0,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        rotation = line.rotation,
                        vertical = vertical,
                    )
                }
            }
        }
        val hit = OcrHitTester.hitLines(lines, viewX, viewY) ?: return null

        val target = hit.value
        if (hit.lineOffset !in target.line.text.indices ||
            !isOcrLookupStartChar(target.line.text[hit.lineOffset])
        ) return null
        val lookupText = if (scanWholeWord) {
            val line = target.line.text
            extractWholeWord(line, hit.lineOffset, 0, line.length)
        } else {
            extractOcrLookupText(target.line.text, hit.lineOffset)
        }
        if (lookupText.isBlank()) return null
        val rect = target.rect
        val location = IntArray(2)
        getLocationOnScreen(location)

        return NovelOcrLookup(
            lookupText = lookupText,
            sentence = target.block.displayText,
            sentenceOffset = hit.textOffset,
            x = rect.left + location[0],
            y = rect.top + location[1],
            width = rect.width(),
            height = rect.height(),
            vertical = target.vertical,
        )
    }

    private fun blockViewRect(block: NovelOcrBlock): RectF? {
        val topLeft = sourceToViewCoord(block.left * sWidth, block.top * sHeight) ?: return null
        val bottomRight = sourceToViewCoord(block.right * sWidth, block.bottom * sHeight) ?: return null
        return RectF(
            minOf(topLeft.x, bottomRight.x),
            minOf(topLeft.y, bottomRight.y),
            maxOf(topLeft.x, bottomRight.x),
            maxOf(topLeft.y, bottomRight.y),
        )
    }

    private fun lineViewRect(line: NovelOcrLine): RectF? {
        val topLeft = sourceToViewCoord(line.left * sWidth, line.top * sHeight) ?: return null
        val bottomRight = sourceToViewCoord(line.right * sWidth, line.bottom * sHeight) ?: return null
        return RectF(
            minOf(topLeft.x, bottomRight.x),
            minOf(topLeft.y, bottomRight.y),
            maxOf(topLeft.x, bottomRight.x),
            maxOf(topLeft.y, bottomRight.y),
        )
    }
}

private val NovelOcrBlock.displayText: String
    get() = if (vertical) text.replace("\n", "") else text.replace('\n', ' ')

private fun NovelOcrLine.isVertical(blockVertical: Boolean): Boolean {
    return OcrHitTester.isLineVertical(blockVertical, left, top, right, bottom)
}

private fun OcrResult.toNovelOcrBlock(): NovelOcrBlock? {
    val box = tightBoundingBox
    val left = box.x.toFloat().coerceIn(0f, 1f)
    val top = box.y.toFloat().coerceIn(0f, 1f)
    val right = (box.x + box.width).toFloat().coerceIn(0f, 1f)
    val bottom = (box.y + box.height).toFloat().coerceIn(0f, 1f)
    val cleanText = text.trim()
    if (cleanText.isEmpty() || right <= left || bottom <= top) return null
    val textLines = cleanText.lines().filter { it.isNotBlank() }
    val lineBoxes = constituentBoxes
        ?.takeIf { it.size == textLines.size }
        ?.zip(textLines)
        ?.map { (lineBox, lineText) ->
            NovelOcrLine(
                text = lineText,
                left = lineBox.x.toFloat().coerceIn(0f, 1f),
                top = lineBox.y.toFloat().coerceIn(0f, 1f),
                right = (lineBox.x + lineBox.width).toFloat().coerceIn(0f, 1f),
                bottom = (lineBox.y + lineBox.height).toFloat().coerceIn(0f, 1f),
                rotation = (lineBox.rotation ?: 0.0).toFloat(),
            )
        }
        ?.filter { it.right > it.left && it.bottom > it.top }
        .orEmpty()
    return NovelOcrBlock(
        text = cleanText,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        vertical = forcedOrientation == "vertical",
        lines = lineBoxes,
    )
}

private fun imageViewerBackground(readerBackgroundColor: Int): Color {
    val base = Color(readerBackgroundColor)
    return if (base.luminance() >= 0.5f) {
        lerp(base, Color.Black, 0.28f)
    } else {
        lerp(base, Color.White, 0.16f)
    }
}

private fun copyImageToClipboard(context: Context, bitmap: Bitmap) {
    val shareDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
    shareDir.listFiles()?.forEach { it.delete() }
    val image = File(shareDir, "novel_image.png")
    FileOutputStream(image).use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", image)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Novel image", uri))
}

private fun saveImage(context: Context, bitmap: Bitmap) {
    val filename = "novel_image_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Mihon")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create image")
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    } else {
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Mihon",
        ).apply { mkdirs() }
        val image = File(directory, filename)
        FileOutputStream(image).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        MediaScannerConnection.scanFile(context, arrayOf(image.absolutePath), arrayOf("image/png"), null)
    }
}

