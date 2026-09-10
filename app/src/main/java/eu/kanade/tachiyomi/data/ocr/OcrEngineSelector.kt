package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import chimahon.ocr.LensClient
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.ocr.processImageWithChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream

enum class OcrEngineType {
    CLOUD,
    LOCAL,
    PADDLE,
    ;

    companion object {
        fun fromPreference(value: String): OcrEngineType {
            return when (value) {
                "local" -> LOCAL
                "paddle" -> PADDLE
                else -> CLOUD
            }
        }
    }
}

suspend fun recognizePage(
    bytes: ByteArray,
    language: OcrLanguage,
    engineType: OcrEngineType? = null,
): List<OcrResult> {
    val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
    val resolvedEngineType = engineType ?: OcrEngineType.fromPreference(dictPrefs.ocrEngine().get())

    if (resolvedEngineType == OcrEngineType.LOCAL) {
        val localOcrBridge = Injekt.get<LocalOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local selected but models not downloaded, triggering download" }
            modelDownloader.triggerDownload()
            return emptyList()
        }
        if (!localOcrBridge.isAvailable) return emptyList()
        if (!localOcrBridge.isInitialized) {
            localOcrBridge.init()
        }
        if (!localOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bytes, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = localOcrBridge.recognize(chunkBytes, language)
            chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine returned no results" }
            return emptyList()
        }
        return result
    }

    if (resolvedEngineType == OcrEngineType.PADDLE) {
        val paddleOcrBridge = Injekt.get<PaddleOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isPaddleDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle selected but models not downloaded, triggering download" }
            modelDownloader.triggerPaddleDownload()
            return emptyList()
        }
        if (!paddleOcrBridge.isAvailable) return emptyList()
        if (!paddleOcrBridge.isInitialized) {
            paddleOcrBridge.init()
        }
        if (!paddleOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bytes, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = paddleOcrBridge.recognize(chunkBytes, language)
            chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine returned no results" }
            return emptyList()
        }
        return result
    }

    val lensClient = Injekt.get<LensClient>()
    val debugResult = lensClient.getDebugOcrData(bytes = bytes, language = language)
    return debugResult.mergedResults
}

suspend fun recognizePage(
    bitmap: Bitmap,
    language: OcrLanguage,
    engineType: OcrEngineType? = null,
): List<OcrResult> {
    val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
    val resolvedEngineType = engineType ?: OcrEngineType.fromPreference(dictPrefs.ocrEngine().get())

    if (resolvedEngineType == OcrEngineType.LOCAL) {
        val localOcrBridge = Injekt.get<LocalOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local selected but models not downloaded, triggering download" }
            modelDownloader.triggerDownload()
            return emptyList()
        }
        if (!localOcrBridge.isAvailable) return emptyList()
        if (!localOcrBridge.isInitialized) {
            localOcrBridge.init()
        }
        if (!localOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bitmap, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = localOcrBridge.recognize(chunkBytes, language)
            if (chunk.bitmap !== bitmap) chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "local engine returned no results" }
            return emptyList()
        }
        return result
    }

    if (resolvedEngineType == OcrEngineType.PADDLE) {
        val paddleOcrBridge = Injekt.get<PaddleOcrBridge>()
        val modelDownloader = Injekt.get<ModelDownloader>()
        if (!modelDownloader.isPaddleDownloaded) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle selected but models not downloaded, triggering download" }
            modelDownloader.triggerPaddleDownload()
            return emptyList()
        }
        if (!paddleOcrBridge.isAvailable) return emptyList()
        if (!paddleOcrBridge.isInitialized) {
            paddleOcrBridge.init()
        }
        if (!paddleOcrBridge.isInitialized) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine failed to initialize" }
            return emptyList()
        }
        val result = processImageWithChunks(bitmap, language) { chunk ->
            val chunkBytes = withContext(Dispatchers.Default) {
                chunk.bitmap.toJpegBytes(85)
            }
            val lines = paddleOcrBridge.recognize(chunkBytes, language)
            if (chunk.bitmap !== bitmap) chunk.bitmap.recycle()
            lines
        }
        if (result.isEmpty()) {
            logcat("OcrEngineSelector", LogPriority.WARN) { "paddle engine returned no results" }
            return emptyList()
        }
        return result
    }

    val lensClient = Injekt.get<LensClient>()
    val debugResult = lensClient.getDebugOcrData(bitmap = bitmap, language = language)
    return debugResult.mergedResults
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    return ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}
