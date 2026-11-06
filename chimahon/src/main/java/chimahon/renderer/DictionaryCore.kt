package chimahon.renderer

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** Narrow, native-owned preparation ABI. No Hoshidicts or WebView entry points change. */
@Keep
object DictionaryCore {
    init {
        val override = if (mihon.chimahon.BuildConfig.DEBUG) System.getProperty("chimahon.debug.dictionaryCore") else null
        if (override == null) System.loadLibrary("dictionary_core") else System.load(override)
    }
    external fun closeContext(handle: Long)
    external fun openBatch(css: Array<String>, fontSize: Float, dark: Boolean, expandDetails: Boolean): Long
    external fun prepareDrawing(context: Long, sources: Array<String>, sheets: IntArray, widths: FloatArray,
        foreground: Int, border: Int): Long
    external fun drawingCounts(handle: Long): IntArray
    external fun drawingSection(handle: Long, section: Int): ByteBuffer
    external fun finishDrawing(handle: Long)
    external fun closeDrawing(handle: Long)
}
