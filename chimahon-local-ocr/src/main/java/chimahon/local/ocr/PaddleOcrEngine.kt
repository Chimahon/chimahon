package chimahon.local.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import chimahon.ocr.EngineLine
import chimahon.ocr.NormalizedBBox
import chimahon.ocr.OcrBitmapDecoder
import chimahon.ocr.OcrLanguage
import chimahon.ocr.WritingDirection
import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore

// Detection/recognition thresholds copied from the ocrtest "Full Page Preset"
// (full-page manga mode). Bubble preset (0.20/0.45/1.60) is intentionally not
// used: page OCR always runs full-page, and reading-order/vertical/furigana
// postprocessing already happens inside the native engine.
data class PaddleOcrOptions(
    val thresh: Float = 0.15f,
    val boxThresh: Float = 0.25f,
    val unclipRatio: Float = 1.40f,
    val filterFurigana: Boolean = false,
    val numThreads: Int = 4,
)

data class PaddleOcrResult(
    val lines: List<EngineLine>,
    val totalMs: Long,
    val prepMs: Long,
    val detMs: Long,
    val postMs: Long,
    val cropMs: Long,
    val recMs: Long,
    val rawBoxCount: Int,
    val furiganaCount: Int,
) {
    val benchmark: String
        get() = "Total: ${totalMs}ms (Prep: ${prepMs}ms, Det: ${detMs}ms, Post: ${postMs}ms, Crop: ${cropMs}ms, Rec: ${recMs}ms) | Boxes: $rawBoxCount, Lines: ${lines.size}"
}

class PaddleOcrEngine(
    context: Context,
) : chimahon.ocr.OcrEngine, Closeable {
    private val TAG = "PaddleOcrEngine"
    private val appContext = context.applicationContext

    override val name: String = "Paddle OCR"

    // The JNI layer owns a single global engine instance, so all locking is
    // companion-wide (never per-instance): two instances must still exclude
    // each other. Scheme, mirroring ocrtest's MangaOcrEngine intent
    // (@Synchronized is illegal on suspend functions, hence explicit locks):
    // - [lock]: held only for init/destroy/close. Brief, never waits on permits.
    // - [inferPermits]: bounds concurrent native inferences. Inference never
    //   takes [lock] except via init(), which never waits on permits.
    // That ordering (permits -> lock, never lock -> permits) cannot deadlock,
    // and init can never free g_engine mid-inference (the SIGSEGV we hit).
    @Volatile
    private var isInitialized = false

    companion object {
        private const val MODEL_DIR = "paddle_ocr"
        private const val DET_PARAM = "manga_det_fp16.param"
        private const val DET_BIN = "manga_det_fp16.bin"
        private const val REC_PARAM = "manga_rec_fp16.param"
        private const val REC_BIN = "manga_rec_fp16.bin"
        private const val DICT_TXT = "ppocrv6_dict.txt"

        /**
         * Max concurrent native inferences, scaled by CPU count (not RAM: no
         * device-specific memory paths). Each job already fans out to
         * [deviceThreads] native threads, so one job saturates a small
         * device; big devices get a second slot to overlap memory stalls.
         * Bounded on both: small devices serialize, big ones overlap two.
         */
        private val INFER_PERMITS: Int =
            if (Runtime.getRuntime().availableProcessors() >= 8) 2 else 1

        private val lock = Any()
        private val inferPermits = Semaphore(INFER_PERMITS)
    }

    /** Big-core count (ported from ocrtest), computed once per process. */
    private val deviceThreads: Int by lazy { detectPerformanceCores().coerceIn(1, 4) }

    private fun supportedAbi(): String? = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
        else -> null
    }

    fun init() = init(PaddleOcrOptions(numThreads = deviceThreads))

    fun init(options: PaddleOcrOptions): Boolean = synchronized(lock) {
        if (isInitialized) return true

        val abi = supportedAbi()
        if (abi == null) {
            Log.e(TAG, "Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            return false
        }
        // Dependency order matters: STL + OpenMP first, engine last.
        // Unlike ocrtest (APK-bundled .so via loadLibrary), the prebuilt libs
        // live in downloaded storage, so they are loaded by absolute path.
        try {
            val libDir = File(File(appContext.filesDir, MODEL_DIR), "lib/$abi")
            System.load(File(libDir, "libc++_shared.so").absolutePath)
            System.load(File(libDir, "libomp.so").absolutePath)
            System.load(File(libDir, "libpaddle_ocr.so").absolutePath)
            Log.i(TAG, "libpaddle_ocr.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libpaddle_ocr.so", e)
            return false
        }

        val start = SystemClock.elapsedRealtime()
        val modelDir = File(appContext.filesDir, MODEL_DIR)
        val success = nativeInit(
            detParamAbsPath = File(modelDir, DET_PARAM).absolutePath,
            detBinAbsPath = File(modelDir, DET_BIN).absolutePath,
            recParamAbsPath = File(modelDir, REC_PARAM).absolutePath,
            recBinAbsPath = File(modelDir, REC_BIN).absolutePath,
            dictAbsPath = File(modelDir, DICT_TXT).absolutePath,
            numThreads = options.numThreads,
        )
        if (success) {
            isInitialized = true
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.i(TAG, "Paddle OCR engine initialized in ${elapsed}ms")
        } else {
            Log.e(TAG, "Failed to initialize Paddle OCR engine from ${modelDir.absolutePath}")
        }
        return success
    }

    fun isInitialized(): Boolean = isInitialized

    override suspend fun recognize(
        bytes: ByteArray,
        language: OcrLanguage,
    ): List<EngineLine> {
        // Bounded parallelism: suspends (never blocks a thread) until a
        // native slot frees up.
        inferPermits.acquire()
        try {
            if (!isInitialized) {
                init()
            }
            if (!isInitialized) return emptyList()
            val bitmap = try {
                OcrBitmapDecoder.decode(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode image for paddle OCR", e)
                return emptyList()
            }
            return try {
                recognizeLocked(bitmap, PaddleOcrOptions(numThreads = deviceThreads), language).lines
            } finally {
                bitmap.recycle()
            }
        } finally {
            inferPermits.release()
        }
    }

    /**
     * Bitmap inference. Must only be called while holding an [inferPermits]
     * permit (see [recognize]); takes [lock] briefly for lazy init, which
     * never waits on permits, so no deadlock is possible.
     */
    private fun recognizeLocked(
        bitmap: Bitmap,
        options: PaddleOcrOptions,
        language: OcrLanguage,
    ): PaddleOcrResult {
        if (!isInitialized) {
            init(options)
        }
        if (!isInitialized) {
            return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)
        }

        val software = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)
        }
        try {
            val raw = nativeProcess(
                bitmap = software,
                thresh = options.thresh,
                boxThresh = options.boxThresh,
                unclipRatio = options.unclipRatio,
                filterFurigana = options.filterFurigana,
                numThreads = options.numThreads,
            )
            val result = parseOutput(raw, language, software.width, software.height)
            Log.i(TAG, "recognize: ${result.benchmark}")
            return result
        } finally {
            if (software !== bitmap) software.recycle()
        }
    }

    private fun parseOutput(raw: String, language: OcrLanguage, imgW: Int, imgH: Int): PaddleOcrResult {
        if (raw.isBlank()) return PaddleOcrResult(emptyList(), 0, 0, 0, 0, 0, 0, 0, 0)

        var totalMs = 0L
        var prepMs = 0L
        var detMs = 0L
        var postMs = 0L
        var cropMs = 0L
        var recMs = 0L
        var rawBoxCount = 0
        var furiganaCount = 0
        val lines = mutableListOf<EngineLine>()

        val rawLines = raw.split("\n")
        for (lineStr in rawLines) {
            if (lineStr.isBlank()) continue
            if (lineStr.startsWith("TIMING|")) {
                val parts = lineStr.split("|")
                if (parts.size >= 8) {
                    totalMs = parts[1].toLongOrNull() ?: 0L
                    prepMs = parts[2].toLongOrNull() ?: 0L
                    detMs = parts[3].toLongOrNull() ?: 0L
                    postMs = parts[4].toLongOrNull() ?: 0L
                    cropMs = parts[5].toLongOrNull() ?: 0L
                    recMs = parts[6].toLongOrNull() ?: 0L
                    rawBoxCount = parts[7].toIntOrNull() ?: 0
                }
            } else if (lineStr.startsWith("LINE|")) {
                val parts = lineStr.split("|", limit = 8)
                if (parts.size >= 8) {
                    val orderId = parts[1].toIntOrNull() ?: 0
                    val score = parts[2].toFloatOrNull() ?: 0f
                    val bboxCoords = parts[3].split(",")
                    val ptsCoords = parts[4].split(",")
                    val isVertical = (parts[5] == "1")
                    // NOTE: parts[6] (native is_furigana flag) is intentionally
                    // ignored. Ruby filtering lives solely in OwOCRMerger so all
                    // engines behave the same; filterFurigana stays off.
                    val isFurigana = (parts[6] == "1")
                    val text = parts[7]

                    if (isFurigana) furiganaCount++

                    if (text.isBlank()) continue

                    if (bboxCoords.size == 4) {
                        val left = bboxCoords[0].toDoubleOrNull() ?: continue
                        val top = bboxCoords[1].toDoubleOrNull() ?: continue
                        val right = bboxCoords[2].toDoubleOrNull() ?: continue
                        val bottom = bboxCoords[3].toDoubleOrNull() ?: continue

                        lines.add(
                            EngineLine(
                                text = text,
                                bbox = NormalizedBBox(
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom,
                                    rotation = quadRotation(ptsCoords, imgW, imgH),
                                ),
                                writingDirection = if (isVertical) WritingDirection.TTB else WritingDirection.LTR,
                                language = language,
                            ),
                        )
                    }
                }
            }
        }

        return PaddleOcrResult(
            lines = lines,
            totalMs = totalMs,
            prepMs = prepMs,
            detMs = detMs,
            postMs = postMs,
            cropMs = cropMs,
            recMs = recMs,
            rawBoxCount = rawBoxCount,
            furiganaCount = furiganaCount,
        )
    }

    /**
     * Skew angle (radians) of the box top edge, or 0 when the quad is missing
     * or near-axis-aligned. Native emits corners ordered tl,tr,br,bl, so
     * pts[0]->pts[1] is the top edge. Folded into [-PI/4, PI/4] with a
     * deadband: the merger refuses grouping across ~0.1 rad rotation gaps,
     * so tiny angles snap to 0 and only genuinely skewed boxes carry
     * rotation (overlay painter and hit-tester consume it).
     */
    private fun quadRotation(ptsCoords: List<String>, imgW: Int, imgH: Int): Double {
        if (ptsCoords.size != 8 || imgW <= 0 || imgH <= 0) return 0.0
        val x1 = ptsCoords[0].toDoubleOrNull() ?: return 0.0
        val y1 = ptsCoords[1].toDoubleOrNull() ?: return 0.0
        val x2 = ptsCoords[2].toDoubleOrNull() ?: return 0.0
        val y2 = ptsCoords[3].toDoubleOrNull() ?: return 0.0
        val dx = (x2 - x1) * imgW
        val dy = (y2 - y1) * imgH
        if (dx == 0.0 && dy == 0.0) return 0.0
        var angle = atan2(dy, dx)
        while (angle > PI / 4) angle -= PI / 2
        while (angle < -PI / 4) angle += PI / 2
        return if (abs(angle) < 0.05) 0.0 else angle
    }

    fun destroy() {
        // Drain in-flight inferences BEFORE taking [lock] (never the reverse):
        // permit holders only ever wait on [lock] briefly inside lazy init,
        // and [lock] holders never wait on permits, so this cannot deadlock.
        // Late arrivals block on permits, then re-init after initialized=false.
        runBlocking {
            repeat(INFER_PERMITS) { inferPermits.acquire() }
        }
        try {
            synchronized(lock) {
                if (isInitialized) {
                    try {
                        nativeRelease()
                    } catch (_: Throwable) {
                    }
                    isInitialized = false
                    Log.i(TAG, "Paddle OCR Engine closed")
                }
            }
        } finally {
            repeat(INFER_PERMITS) { inferPermits.release() }
        }
    }

    override fun close() {
        destroy()
    }

    private external fun nativeInit(
        detParamAbsPath: String,
        detBinAbsPath: String,
        recParamAbsPath: String,
        recBinAbsPath: String,
        dictAbsPath: String,
        numThreads: Int,
    ): Boolean

    private external fun nativeProcess(
        bitmap: Bitmap,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        filterFurigana: Boolean,
        numThreads: Int,
    ): String

    private external fun nativeRelease()

    /**
     * Big-core count, ported from ocrtest. Cores whose max frequency is
     * within 15% of the fastest core count as performance cores; falls back
     * to half the cores on devices without readable cpufreq nodes.
     */
    private fun detectPerformanceCores(): Int {
        try {
            val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val freqs = mutableListOf<Long>()
            for (i in 0 until totalCores) {
                val file = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (file.exists() && file.canRead()) {
                    file.readText().trim().toLongOrNull()?.takeIf { it > 0 }?.let { freqs.add(it) }
                }
            }
            if (freqs.isNotEmpty()) {
                val maxFreq = freqs.maxOrNull() ?: 0L
                val bigCores = freqs.count { it >= (maxFreq * 0.85).toLong() }
                if (bigCores in 1..totalCores) return bigCores
            }
            if (totalCores >= 8) return 4
            return (totalCores / 2).coerceAtLeast(1)
        } catch (_: Throwable) {
            return 4.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        }
    }
}
