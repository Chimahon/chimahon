package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.EngineLine
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PaddleOcrBridge(private val context: Context) {

    private var engine: OcrEngine? = null

    val isAvailable: Boolean get() = BuildConfig.HAS_LOCAL_OCR
    var isInitialized: Boolean = false
        private set

    fun init(): Boolean {
        if (!isAvailable) return false
        if (isInitialized) return true

        try {
            val clazz = Class.forName("chimahon.local.ocr.PaddleOcrEngine")
            val constructor = clazz.getConstructor(Context::class.java)
            val instance = constructor.newInstance(context) as OcrEngine
            val initMethod = clazz.getMethod("init")
            initMethod.invoke(instance)

            val initializedField = clazz.getMethod("isInitialized")
            val initialized = initializedField.invoke(instance) as Boolean

            if (initialized) {
                engine = instance
                isInitialized = true
                logcat { "PaddleOcrBridge: PaddleOcrEngine initialized successfully" }
            } else {
                logcat(LogPriority.WARN) { "PaddleOcrBridge: PaddleOcrEngine.init() returned not initialized" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOcrBridge: failed to init PaddleOcrEngine" }
        }
        return isInitialized
    }

    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        if (!isInitialized) return emptyList()
        return engine?.recognize(bytes, language) ?: emptyList()
    }

    fun destroy() {
        if (engine != null) {
            try {
                val destroyMethod = engine!!.javaClass.getMethod("destroy")
                destroyMethod.invoke(engine)
            } catch (_: Exception) {}
            engine = null
            isInitialized = false
        }
    }
}
