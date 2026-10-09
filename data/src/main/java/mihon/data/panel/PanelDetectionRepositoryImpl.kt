package mihon.data.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.panel.model.DebugPanelDetection
import mihon.domain.panel.model.PanelDetectionResult
import mihon.domain.panel.repository.PanelDetectionRepository
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.ReadingDirection
import tachiyomi.core.common.util.system.ReadingOrderSorter
import tachiyomi.core.common.util.system.flattenToString
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

class PanelDetectionRepositoryImpl(
    private val context: Context,
) : PanelDetectionRepository {

    @Volatile
    private var nativesLoaded = false

    private val environment by lazy { Environment.create() }

    private var engine: YoloPanelDetectionEngine? = null
    private val engineMutex = Mutex()

    private fun getEngine(): YoloPanelDetectionEngine? {
        engine?.let { return it }
        val modelFile = panelModelFile()
        if (modelFile == null || !modelFile.isFile) {
            logcat(LogPriority.DEBUG) {
                "Panel detector engine not initialized: model not downloaded"
            }
            return null
        }
        if (!loadNatives()) {
            logcat(LogPriority.WARN) { "Panel detector engine not initialized: native library load failed" }
            return null
        }
        return YoloPanelDetectionEngine(modelFile, environment).also {
            engine = it
        }
    }

    /**
     * Loads the LiteRT native libraries from filesDir in dependency order.
     * They are downloaded at runtime (not bundled in the APK) so they must be
     * loaded manually before any LiteRT class touches the JNI layer.
     */
    private fun loadNatives(): Boolean {
        if (nativesLoaded) return true
        val abi = supportedAbi() ?: return false
        val libDir = File(File(context.filesDir, MODEL_ROOT_DIR), "lib")
        val liteRt = File(libDir, "$abi/libLiteRt.so")
        val litertJni = File(libDir, "$abi/liblitert_jni.so")
        if (!liteRt.isFile || liteRt.length() == 0L || !litertJni.isFile || litertJni.length() == 0L) {
            logcat(LogPriority.DEBUG) {
                "Panel detector native libs not downloaded (LiteRt=${liteRt.isFile} jni=${litertJni.isFile})"
            }
            return false
        }
        return try {
            System.load(liteRt.absolutePath)
            System.load(litertJni.absolutePath)
            nativesLoaded = true
            true
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load LiteRT native libraries" }
            false
        }
    }

    private fun supportedAbi(): String? = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
        "x86_64" in Build.SUPPORTED_ABIS -> "x86_64"
        else -> null
    }

    private fun panelModelFile(): File? {
        val abi = supportedAbi() ?: return null
        val libDir = File(File(context.filesDir, MODEL_ROOT_DIR), "lib")
        if (!File(libDir, "$abi/libLiteRt.so").let { it.isFile && it.length() > 0 }) return null
        if (!File(libDir, "$abi/liblitert_jni.so").let { it.isFile && it.length() > 0 }) return null
        return File(File(context.filesDir, MODEL_ROOT_DIR), "model.tflite").let { if (it.isFile && it.length() > 0) it else null }
    }

    override suspend fun detectPanels(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult = engineMutex.withLock {
        try {
            val engine = getEngine()
            if (engine == null) {
                logcat(LogPriority.DEBUG) {
                    "Panel detection skipped key=$cacheKey (engine not available)"
                }
                PanelDetectionResult.EMPTY
            } else {
                engine.detectPanels(
                    cacheKey = cacheKey,
                    image = image,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    direction = direction,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Panel detection failed for $cacheKey" }
            PanelDetectionResult.EMPTY
        }
    }

    override suspend fun invalidateModels() {
        engineMutex.withLock { cleanup() }
    }

    override fun cleanup() {
        try {
            engine?.close()
            engine = null
            logcat(LogPriority.INFO) { "PanelDetectionRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up panel detector" }
        }
    }

    companion object {
        private const val MODEL_ROOT_DIR = "panel_detector"
    }
}

/**
 * A YOLO-based panel detection engine backed by the LiteRT runtime.
 * The model is loaded from a file (downloaded at runtime) rather than from assets.
 */
private class YoloPanelDetectionEngine(
    modelFile: File,
    environment: Environment,
) : Closeable {

    private val compiledModel: CompiledModel
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>
    private val inferenceMutex = Mutex()

    private val scratchBitmap = createBitmap(INPUT_SIZE, INPUT_SIZE)
    private val scratchCanvas = Canvas(scratchBitmap)
    private val scratchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        isAntiAlias = true
    }
    private val sourceRect = Rect()
    private val destinationRect = Rect()
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputFloatBuffer = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

    private val cache = object : LinkedHashMap<String, PanelDetectionResult>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PanelDetectionResult>?): Boolean {
            return size > CACHE_CAPACITY
        }
    }
    private val cacheMutex = Mutex()

    init {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
        }

        compiledModel = CompiledModel.create(
            modelFile.absolutePath,
            options,
            environment,
        )
        inputBuffers = compiledModel.createInputBuffers()
        outputBuffers = compiledModel.createOutputBuffers()
        check(inputBuffers.size == 1) { "Panel detector expected 1 input but found ${inputBuffers.size}" }
        check(outputBuffers.isNotEmpty()) { "Panel detector expected at least 1 output buffer" }

        logcat(LogPriority.INFO) {
            "Panel detector initialized (cpuThreads=$cpuThreads, outputs=${outputBuffers.size})"
        }
    }

    suspend fun detectPanels(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult {
        cacheMutex.withLock {
            cache[cacheEntryKey(cacheKey, direction)]?.let {
                return it.copy(cacheHit = true)
            }
        }

        var preprocessNanos = 0L
        var inferenceNanos = 0L
        val detections = mutableListOf<ScoredDetection>()

        val totalStart = System.nanoTime()
        inferenceMutex.withLock {
            val preprocessing = preprocessImage(
                image = image,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            )
            preprocessNanos = preprocessing.durationNanos

            inputBuffers[0].writeFloat(inputFloatBuffer)

            inferenceNanos = measureNanoTime {
                compiledModel.run(inputBuffers, outputBuffers)
            }

            val rawOutputs = outputBuffers.map { it.readFloat() }

            detections += parseDetections(
                rawOutputs = rawOutputs,
                mapping = preprocessing.mapping,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            )
        }
        val totalNanos = System.nanoTime() - totalStart

        val result = buildResult(
            cacheKey = cacheKey,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            direction = direction,
            preprocessNanos = preprocessNanos,
            inferenceNanos = inferenceNanos,
            totalNanos = totalNanos,
            detections = detections,
        )

        cacheMutex.withLock {
            cache[cacheEntryKey(cacheKey, direction)] = result
        }
        return result
    }

    private fun buildResult(
        cacheKey: String,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
        preprocessNanos: Long,
        inferenceNanos: Long,
        totalNanos: Long,
        detections: List<ScoredDetection>,
    ): PanelDetectionResult {
        val panelDetections = detections.filter { it.classId == PANEL_CLASS_ID }
        val bubbleDetections = detections.filter {
            it.classId == BUBBLE_CLASS_ID && it.confidence >= BUBBLE_CONFIDENCE_THRESHOLD
        }

        val prunedPanels = removeHeavyOverlaps(
            panelDetections
                .filter { it.confidence >= CONFIDENCE_THRESHOLD }
                .filter { it.rect.width() > 0 && it.rect.height() > 0 }
                .filter { isLargeEnough(it.rect, originalWidth, originalHeight) },
        )
        val prunedBubbles = removeHeavyOverlaps(
            bubbleDetections
                .filter { it.rect.width() > 0 && it.rect.height() > 0 },
        )

        val debugPanels = prunedPanels
            .sortedByDescending { it.confidence }
            .map { DebugPanelDetection(rect = Rect(it.rect), confidence = it.confidence) }
        val debugBubbles = prunedBubbles
            .sortedByDescending { it.confidence }
            .map { DebugPanelDetection(rect = Rect(it.rect), confidence = it.confidence) }

        val orderedRects = sortByReadingOrder(
            rects = prunedPanels.map { it.rect },
            direction = direction,
        )
        val expandedRects = expandLargePanels(
            panels = orderedRects,
            bubbles = prunedBubbles.map { it.rect },
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            direction = direction,
        )
        val orderedPanels = expandedRects.map(::Panel)

        val result = PanelDetectionResult(
            panels = orderedPanels,
            debugPanels = debugPanels,
            debugBubbles = debugBubbles,
            preprocessMillis = preprocessNanos / 1_000_000,
            inferenceMillis = inferenceNanos / 1_000_000,
            totalMillis = totalNanos / 1_000_000,
        )

        logcat(LogPriority.INFO) {
            "Panel detector result key=$cacheKey panels=${result.panels.size} bubbles=${prunedBubbles.size} " +
                "preprocessMs=${result.preprocessMillis} inferenceMs=${result.inferenceMillis} totalMs=${result.totalMillis}"
        }

        return result
    }

    private fun preprocessImage(
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
    ): PreprocessResult {
        lateinit var mapping: LetterboxMapping
        val durationNanos = measureNanoTime {
            val pageBounds = findContentBounds(image)
            sourceRect.set(pageBounds)

            scratchCanvas.drawColor(Color.WHITE)

            val cropWidth = pageBounds.width().coerceAtLeast(1)
            val cropHeight = pageBounds.height().coerceAtLeast(1)
            val scale = minOf(INPUT_SIZE / cropWidth.toFloat(), INPUT_SIZE / cropHeight.toFloat())

            val contentWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
            val contentHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
            val padX = ((INPUT_SIZE - contentWidth) / 2f)
            val padY = ((INPUT_SIZE - contentHeight) / 2f)

            destinationRect.set(
                padX.roundToInt(),
                padY.roundToInt(),
                (padX + contentWidth).roundToInt(),
                (padY + contentHeight).roundToInt(),
            )
            scratchCanvas.drawBitmap(image, sourceRect, destinationRect, scratchPaint)

            scratchBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            var outputIndex = 0
            pixelBuffer.forEach { pixel ->
                inputFloatBuffer[outputIndex++] = ((pixel shr 16) and 0xFF) / 255f
                inputFloatBuffer[outputIndex++] = ((pixel shr 8) and 0xFF) / 255f
                inputFloatBuffer[outputIndex++] = (pixel and 0xFF) / 255f
            }

            mapping = LetterboxMapping(
                sampledWidth = image.width,
                sampledHeight = image.height,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                cropLeft = pageBounds.left,
                cropTop = pageBounds.top,
                scale = scale,
                padX = padX,
                padY = padY,
            )

            logcat(LogPriority.DEBUG) {
                "Panel detector preprocess image=${image.width}x${image.height} " +
                    "original=${originalWidth}x$originalHeight " +
                    "contentBounds=${pageBounds.flattenToString()} " +
                    "crop=${cropWidth}x$cropHeight scale=$scale " +
                    "pad=$padX,$padY dest=${destinationRect.flattenToString()}"
            }
        }

        return PreprocessResult(
            durationNanos = durationNanos,
            mapping = mapping,
        )
    }

    private fun parseDetections(
        rawOutputs: List<FloatArray>,
        mapping: LetterboxMapping,
        originalWidth: Int,
        originalHeight: Int,
    ): List<ScoredDetection> {
        if (rawOutputs.isEmpty()) return emptyList()

        logcat(LogPriority.DEBUG) {
            "Panel detector rawOutputs count=${rawOutputs.size} sizes=${rawOutputs.map { it.size }} " +
                "expected=${PREFERRED_OUTPUT_COUNT * DETECTION_STRIDE}"
        }

        val preferredValues = rawOutputs.firstOrNull { it.size == PREFERRED_OUTPUT_COUNT * DETECTION_STRIDE }
        if (preferredValues == null) {
            logcat(LogPriority.WARN) { "Panel detector no matching output buffer found" }
            return emptyList()
        }

        // Log top 5 raw detections before any mapping
        val topRaw = (0 until minOf(5, PREFERRED_OUTPUT_COUNT)).map { i ->
            val off = i * DETECTION_STRIDE
            "x0=%.3f y0=%.3f x1=%.3f y1=%.3f conf=%.3f cls=%.0f".format(
                preferredValues[off],
                preferredValues[off + 1],
                preferredValues[off + 2],
                preferredValues[off + 3],
                preferredValues[off + 4],
                preferredValues[off + 5],
            )
        }
        logcat(LogPriority.DEBUG) { "Panel detector top5 raw: ${topRaw.joinToString(" | ")}" }

        return parseCombinedTensor(
            values = preferredValues,
            detectionCount = PREFERRED_OUTPUT_COUNT,
            transposed = false,
            coordinateEncoding = CoordinateEncoding.XYXY,
            normalized = true,
            mapping = mapping,
            minConfidence = 0f,
        )
    }

    private fun parseCombinedTensor(
        values: FloatArray,
        detectionCount: Int,
        transposed: Boolean,
        coordinateEncoding: CoordinateEncoding,
        normalized: Boolean,
        mapping: LetterboxMapping,
        minConfidence: Float,
    ): List<ScoredDetection> {
        if (detectionCount <= 0) return emptyList()

        return (0 until detectionCount).mapNotNull { index ->
            val chunk = FloatArray(DETECTION_STRIDE) { slot ->
                if (transposed) {
                    values[(slot * detectionCount) + index]
                } else {
                    values[(index * DETECTION_STRIDE) + slot]
                }
            }

            parseDetectionChunk(
                x0 = chunk[0],
                y0 = chunk[1],
                x1 = chunk[2],
                y1 = chunk[3],
                confidenceRaw = chunk[4],
                classRaw = chunk[5],
                coordinateEncoding = coordinateEncoding,
                normalized = normalized,
                mapping = mapping,
                minConfidence = minConfidence,
            )
        }
    }

    private fun parseDetectionChunk(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        confidenceRaw: Float,
        classRaw: Float,
        coordinateEncoding: CoordinateEncoding,
        normalized: Boolean,
        mapping: LetterboxMapping,
        minConfidence: Float,
    ): ScoredDetection? {
        if (!confidenceRaw.isFinite() || !classRaw.isFinite()) return null

        val classId = classRaw.roundToInt()
        if ((classId != PANEL_CLASS_ID && classId != BUBBLE_CLASS_ID) || confidenceRaw < minConfidence) return null

        val coordinateScale = if (normalized) INPUT_SIZE.toFloat() else 1f
        val scaledX0 = x0 * coordinateScale
        val scaledY0 = y0 * coordinateScale
        val scaledX1 = x1 * coordinateScale
        val scaledY1 = y1 * coordinateScale

        val (left, top, right, bottom) = when (coordinateEncoding) {
            CoordinateEncoding.XYXY -> BoxCorners(
                left = scaledX0,
                top = scaledY0,
                right = scaledX1,
                bottom = scaledY1,
            )
            CoordinateEncoding.XYWH -> {
                val halfWidth = scaledX1 / 2f
                val halfHeight = scaledY1 / 2f
                BoxCorners(
                    left = scaledX0 - halfWidth,
                    top = scaledY0 - halfHeight,
                    right = scaledX0 + halfWidth,
                    bottom = scaledY0 + halfHeight,
                )
            }
        }

        return mapModelRectToOriginal(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = confidenceRaw,
            classId = classId,
            mapping = mapping,
        )
    }

    private fun mapModelRectToOriginal(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float,
        classId: Int,
        mapping: LetterboxMapping,
    ): ScoredDetection? {
        val sampledLeft = ((left - mapping.padX) / mapping.scale) + mapping.cropLeft
        val sampledTop = ((top - mapping.padY) / mapping.scale) + mapping.cropTop
        val sampledRight = ((right - mapping.padX) / mapping.scale) + mapping.cropLeft
        val sampledBottom = ((bottom - mapping.padY) / mapping.scale) + mapping.cropTop

        val mappedLeft = (sampledLeft * mapping.originalWidth / mapping.sampledWidth.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalWidth)
        val mappedTop = (sampledTop * mapping.originalHeight / mapping.sampledHeight.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalHeight)
        val mappedRight = (sampledRight * mapping.originalWidth / mapping.sampledWidth.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalWidth)
        val mappedBottom = (sampledBottom * mapping.originalHeight / mapping.sampledHeight.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalHeight)

        val rect = Rect(
            minOf(mappedLeft, mappedRight),
            minOf(mappedTop, mappedBottom),
            maxOf(mappedLeft, mappedRight),
            maxOf(mappedTop, mappedBottom),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null

        return ScoredDetection(rect = rect, confidence = confidence, classId = classId)
    }

    private fun removeHeavyOverlaps(panels: List<ScoredDetection>): List<ScoredDetection> {
        if (panels.isEmpty()) return emptyList()

        val sorted = panels.sortedByDescending { it.confidence }
        val kept = mutableListOf<ScoredDetection>()

        sorted.forEach { candidate ->
            if (kept.none { intersectionOverUnion(it.rect, candidate.rect) > MAX_DUPLICATE_IOU }) {
                kept += candidate
            }
        }

        return kept
    }

    private fun expandLargePanels(
        panels: List<Rect>,
        bubbles: List<Rect>,
        imageWidth: Int,
        imageHeight: Int,
        direction: ReadingDirection,
    ): List<Rect> {
        val largeThreshold = (imageWidth * LARGE_PANEL_WIDTH_RATIO).roundToInt()
        return panels.flatMap { panel ->
            if (panel.width() < largeThreshold) {
                listOf(panel)
            } else {
                val contained = bubbles.filter { bubble ->
                    panel.contains(bubble.centerX(), bubble.centerY())
                }
                if (contained.isEmpty()) {
                    listOf(panel)
                } else {
                    val virtual = clusterBubblesIntoVirtualPanels(
                        bubbles = contained,
                        parentPanel = panel,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        direction = direction,
                    )
                    logcat(LogPriority.DEBUG) {
                        "Panel detector expanded large panel ${panel.flattenToString()} " +
                            "into ${virtual.size} virtual panels from ${contained.size} bubbles"
                    }
                    listOf(panel) + virtual
                }
            }
        }
    }

    private fun clusterBubblesIntoVirtualPanels(
        bubbles: List<Rect>,
        parentPanel: Rect,
        imageWidth: Int,
        imageHeight: Int,
        direction: ReadingDirection,
    ): List<Rect> {
        val rowThreshold = (parentPanel.height() * BUBBLE_ROW_GROUPING_RATIO).roundToInt()
        val mergeGap = (parentPanel.width() * BUBBLE_MERGE_GAP_RATIO).roundToInt()

        // Group bubbles into rows by Y-center proximity
        val rows = mutableListOf<MutableList<Rect>>()
        bubbles.sortedBy { it.centerY() }.forEach { bubble ->
            val row = rows.find { existing ->
                abs(existing.first().centerY() - bubble.centerY()) <= rowThreshold
            }
            if (row == null) {
                rows += mutableListOf(bubble)
            } else {
                row += bubble
            }
        }

        // Within each row, merge horizontally close bubbles into clusters
        val clusters = mutableListOf<Rect>()
        rows.forEach { row ->
            val sorted = when (direction) {
                ReadingDirection.RTL -> row.sortedByDescending { it.centerX() }
                else -> row.sortedBy { it.centerX() }
            }
            var cluster = Rect(sorted.first())
            for (i in 1..sorted.lastIndex) {
                val gap = if (direction == ReadingDirection.RTL) {
                    cluster.left - sorted[i].right
                } else {
                    sorted[i].left - cluster.right
                }
                if (gap <= mergeGap) {
                    cluster.union(sorted[i])
                } else {
                    clusters += cluster
                    cluster = Rect(sorted[i])
                }
            }
            clusters += cluster
        }

        // Pad clusters and enforce minimum size, clamped to parent panel
        val minWidth = (imageWidth * MIN_VIRTUAL_WIDTH_RATIO).roundToInt()
        val minHeight = (imageHeight * MIN_VIRTUAL_HEIGHT_RATIO).roundToInt()

        val virtualPanels = clusters.map { cluster ->
            val padX = (cluster.width() * VIRTUAL_PANEL_PADDING).roundToInt()
            val padY = (cluster.height() * VIRTUAL_PANEL_PADDING).roundToInt()
            val padded = Rect(
                cluster.left - padX,
                cluster.top - padY,
                cluster.right + padX,
                cluster.bottom + padY,
            )

            // Enforce minimum size by expanding from center
            if (padded.width() < minWidth) {
                val deficit = (minWidth - padded.width()) / 2
                padded.left -= deficit
                padded.right += deficit
            }
            if (padded.height() < minHeight) {
                val deficit = (minHeight - padded.height()) / 2
                padded.top -= deficit
                padded.bottom += deficit
            }

            // Clamp to parent panel bounds
            Rect(
                padded.left.coerceAtLeast(parentPanel.left),
                padded.top.coerceAtLeast(parentPanel.top),
                padded.right.coerceAtMost(parentPanel.right),
                padded.bottom.coerceAtMost(parentPanel.bottom),
            )
        }

        return sortByReadingOrder(
            rects = virtualPanels,
            direction = direction,
        )
    }

    private fun sortByReadingOrder(
        rects: List<Rect>,
        direction: ReadingDirection,
    ): List<Rect> {
        val sortedIndices = ReadingOrderSorter.sort(rects, direction)
        return sortedIndices.map { rects[it] }
    }

    private fun isLargeEnough(rect: Rect, width: Int, height: Int): Boolean {
        val area = rect.width().toLong() * rect.height().toLong()
        val imageArea = width.toLong() * height.toLong()
        return imageArea > 0 && area.toFloat() / imageArea.toFloat() >= MIN_PANEL_AREA_RATIO
    }

    private fun findContentBounds(bitmap: Bitmap): Rect {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0

        pixels.forEachIndexed { index, pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (maxOf(r, g, b) <= CONTENT_THRESHOLD) return@forEachIndexed

            val x = index % bitmap.width
            val y = index / bitmap.width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }

        if (left >= right || top >= bottom) {
            return Rect(0, 0, bitmap.width, bitmap.height)
        }

        val rect = Rect(left, top, right, bottom)
        val minMeaningfulWidth = (bitmap.width * MIN_CONTENT_SPAN_RATIO).roundToInt()
        val minMeaningfulHeight = (bitmap.height * MIN_CONTENT_SPAN_RATIO).roundToInt()
        return if (rect.width() < minMeaningfulWidth || rect.height() < minMeaningfulHeight) {
            Rect(0, 0, bitmap.width, bitmap.height)
        } else {
            rect
        }
    }

    private fun intersectionOverUnion(first: Rect, second: Rect): Float {
        val intersectionLeft = maxOf(first.left, second.left)
        val intersectionTop = maxOf(first.top, second.top)
        val intersectionRight = minOf(first.right, second.right)
        val intersectionBottom = minOf(first.bottom, second.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val firstArea = first.width() * first.height()
        val secondArea = second.width() * second.height()
        val union = firstArea + secondArea - intersectionArea

        return if (union == 0) 0f else intersectionArea.toFloat() / union.toFloat()
    }

    private fun cacheEntryKey(
        cacheKey: String,
        direction: ReadingDirection,
    ): String {
        return "$cacheKey:${direction.name}"
    }

    override fun close() {
        scratchBitmap.recycle()
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        compiledModel.close()
        cache.clear()
    }

    private data class PreprocessResult(
        val durationNanos: Long,
        val mapping: LetterboxMapping,
    )

    private data class LetterboxMapping(
        val sampledWidth: Int,
        val sampledHeight: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    private data class ScoredDetection(
        val rect: Rect,
        val confidence: Float,
        val classId: Int,
    )

    private data class BoxCorners(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private enum class CoordinateEncoding {
        XYXY,
        XYWH,
    }

    companion object {
        private const val INPUT_SIZE = 640
        private const val DETECTION_STRIDE = 6
        private const val PREFERRED_OUTPUT_COUNT = 300
        private const val PANEL_CLASS_ID = 0
        private const val BUBBLE_CLASS_ID = 1
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val BUBBLE_CONFIDENCE_THRESHOLD = 0.4f
        private const val LARGE_PANEL_WIDTH_RATIO = 0.85f
        private const val BUBBLE_ROW_GROUPING_RATIO = 0.08f
        private const val BUBBLE_MERGE_GAP_RATIO = 0.15f
        private const val VIRTUAL_PANEL_PADDING = 0.30f
        private const val MIN_VIRTUAL_WIDTH_RATIO = 0.35f
        private const val MIN_VIRTUAL_HEIGHT_RATIO = 0.25f
        private const val MIN_PANEL_AREA_RATIO = 0.02f
        private const val MAX_DUPLICATE_IOU = 0.8f
        private const val CONTENT_THRESHOLD = 16
        private const val MIN_CONTENT_SPAN_RATIO = 0.25f
        private const val CACHE_CAPACITY = 128
        private const val DEBUG_OVERLAY_LIMIT = 10
    }
}