package mihon.core.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Presents a PDF as lazily rendered image pages, without changing the original document. */
class PdfReader(context: Context, file: UniFile) : Closeable {
    private val cache = File(context.cacheDir, "pdf_reader_${UUID.randomUUID()}")
    private val renderer: PdfRenderer
    private var closed = false

    init {
        check(cache.mkdirs()) { "Could not create PDF cache" }
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = file.openFileDescriptor(context, "r")
            val seekable = runCatching {
                Os.lseek(requireNotNull(descriptor).fileDescriptor, 0, OsConstants.SEEK_SET)
            }.isSuccess
            if (!seekable) {
                descriptor.close()
                descriptor = null
                val copy = File(cache, "document.pdf")
                file.openInputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
                descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            renderer = PdfRenderer(requireNotNull(descriptor))
            descriptor = null // PdfRenderer owns the descriptor.
        } catch (error: Throwable) {
            descriptor?.close()
            cache.deleteRecursively()
            throw error
        }
    }

    val pageCount: Int get() = renderer.pageCount

    @Synchronized
    fun getInputStream(index: Int): InputStream {
        check(!closed) { "PDF reader is closed" }
        require(index in 0 until pageCount)
        val image = File(cache, "$index.png")
        if (!image.isFile) {
            renderer.openPage(index).use { page ->
                val scale = 2048.0 / max(page.width, page.height).coerceAtLeast(1)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val temporary = File(cache, "$index.tmp")
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    temporary.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not render PDF page" }
                    }
                    check(temporary.renameTo(image)) { "Could not cache PDF page" }
                } finally {
                    bitmap.recycle()
                    temporary.delete()
                }
            }
        }
        return image.inputStream()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            renderer.close()
        } finally {
            cache.deleteRecursively()
        }
    }
}

fun UniFile.pdfReader(context: Context) = PdfReader(context, this)
