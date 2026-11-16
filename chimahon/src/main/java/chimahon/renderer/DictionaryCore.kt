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
    /** Triggers the library load when a caller only needs the lookup JNI. */
    fun ensureLoaded() = Unit

    external fun closeContext(handle: Long)
    external fun openBatch(css: Array<String>, fontSize: Float, dark: Boolean, expandDetails: Boolean, language: String): Long
    external fun prepareDrawing(context: Long, sources: Array<String>, typedSources: Array<ByteBuffer?>, sheets: IntArray, widths: FloatArray,
        foreground: Int, border: Int): Long
    external fun profileBegin(id: Long)
    external fun profileEnd()
    external fun drawingTimings(handle: Long): FloatArray
    external fun drawingCounts(handle: Long): IntArray
    external fun drawingSection(handle: Long, section: Int): ByteBuffer
    external fun finishDrawing(handle: Long)
    external fun measurePortable(handle: Long, pixelScale: Float, fontFiles: Array<String>, fontIndices: IntArray)
    external fun rasterPortable(handle: Long, source: Int, pixelScale: Float, left: Float, top: Float,
        width: Int, height: Int, bitmap: android.graphics.Bitmap, textColor: Int, overrideColor: Boolean)
    external fun closeDrawing(handle: Long)
    external fun placeCard(parts: FloatArray): FloatArray
    external fun lookupWordAt(text: String, offset: Int): String?
    external fun headwordTapeSize(expression: String, reading: String, classes: String): Int
    external fun headwordTapeInto(buffer: ByteBuffer, expression: String, reading: String, classes: String): Int
    external fun splitMorae(text: String): Array<String>
    external fun moraHighFlags(reading: String, accent: Int): BooleanArray
    external fun hitAt(handle: Long, source: Int, x: Float, y: Float): Array<String>?
    external fun selectionRects(handle: Long, source: Int, offset: Int, codePoints: Int): FloatArray
    external fun sourceText(handle: Long, source: Int): String?
}
