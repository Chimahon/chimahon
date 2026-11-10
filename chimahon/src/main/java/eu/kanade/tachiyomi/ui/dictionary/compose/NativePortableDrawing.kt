package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Bitmap
import chimahon.renderer.DictionaryCore
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Shared by every source in a batch. Native font/glyph storage outlives all its surfaces. */
internal class NativePortableDrawing(handle: Long) {
    private val allocation = Allocation(this, handle).also { allocations.add(it) }

    @Synchronized
    fun raster(source: Int, scale: Float, left: Int, top: Int, width: Int, height: Int,
        bitmap: Bitmap, textColor: Int?) {
        val handle = allocation.handle.get()
        check(handle != 0L) { "Portable document is closed" }
        try {
            DictionaryCore.rasterPortable(handle, source, scale, left / scale, top / scale,
                width, height, bitmap, textColor ?: 0, textColor != null)
        } finally { java.lang.ref.Reference.reachabilityFence(this) }
    }

    @Synchronized
    fun hitAt(source: Int, x: Float, y: Float): Array<String>? {
        val handle = allocation.handle.get()
        check(handle != 0L) { "Portable document is closed" }
        try { return DictionaryCore.hitAt(handle, source, x, y) }
        finally { java.lang.ref.Reference.reachabilityFence(this) }
    }

    @Synchronized
    fun selectionRects(source: Int, offset: Int, codePoints: Int): FloatArray {
        val handle = allocation.handle.get()
        check(handle != 0L) { "Portable document is closed" }
        try { return DictionaryCore.selectionRects(handle, source, offset, codePoints) }
        finally { java.lang.ref.Reference.reachabilityFence(this) }
    }

    @Synchronized
    fun text(source: Int): String {
        val handle = allocation.handle.get()
        check(handle != 0L) { "Portable document is closed" }
        try { return DictionaryCore.sourceText(handle, source) ?: "" }
        finally { java.lang.ref.Reference.reachabilityFence(this) }
    }

    @Synchronized
    fun close() = allocation.release()

    private class Allocation(owner: NativePortableDrawing, pointer: Long) : PhantomReference<NativePortableDrawing>(owner, queue) {
        val handle = AtomicLong(pointer)
        fun release() {
            val pointer = handle.getAndSet(0L)
            if (pointer != 0L) DictionaryCore.closeDrawing(pointer)
            allocations.remove(this)
            clear()
        }
    }

    companion object {
        private val queue = ReferenceQueue<NativePortableDrawing>()
        private val allocations = ConcurrentHashMap.newKeySet<Allocation>()
        init {
            Thread({
                while (true) {
                    try { (queue.remove() as Allocation).release() }
                    catch (_: InterruptedException) { /* Cleanup remains available for later documents. */ }
                }
            }, "DictionaryNativeCleanup").apply { isDaemon = true; start() }
        }
    }
}
