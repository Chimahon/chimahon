package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.EngineLine
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class LocalOcrBridge(private val context: Context) {

    private var engine: OcrEngine? = null
    private val engineMutex = Mutex()

    val isAvailable: Boolean get() = BuildConfig.HAS_LOCAL_OCR
    @Volatile
    var isInitialized: Boolean = false
        private set

    @Synchronized
    fun init(): Boolean {
        if (!isAvailable) return false
        if (isInitialized) return true

        try {
            val clazz = Class.forName("chimahon.local.ocr.LensEngine")
            val constructor = clazz.getConstructor(Context::class.java)
            val instance = constructor.newInstance(context) as OcrEngine
            val initMethod = clazz.getMethod("init")
            initMethod.invoke(instance)

            val initializedField = clazz.getMethod("isInitialized")
            val initialized = initializedField.invoke(instance) as Boolean

            if (initialized) {
                engine = instance
                isInitialized = true
                logcat { "LocalOcrBridge: LensEngine initialized successfully" }
            } else {
                logcat(LogPriority.WARN) { "LocalOcrBridge: LensEngine.init() returned not initialized" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LocalOcrBridge: failed to init LensEngine" }
        }
        return isInitialized
    }

    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> = engineMutex.withLock {
        if (!isInitialized && !init()) return@withLock emptyList()
        engine?.recognize(bytes, language) ?: emptyList()
    }

    suspend fun invalidateModels() = engineMutex.withLock { destroy() }

    @Synchronized
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
