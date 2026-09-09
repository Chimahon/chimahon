package chimahon.novel.cache

import android.content.Context
import android.text.format.Formatter
import chimahon.novel.data.novelReaderSettings
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Bounded on-disk cache for source chapter HTML (DiskLruCache, md5 keys,
 * evictable). Local novels are files, not cache: their EPUB extraction is
 * the content. Downloads are sacred files, never here.
 */
class NovelChapterCache(
    private val context: Context,
) {

    @Volatile
    private var currentSizeMb = DEFAULT_SIZE_MB

    @Volatile
    private var diskCache = setupDiskCache(currentSizeMb)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // Manga ChapterCache parity: user-settable size, applied by reopening.
        context.novelReaderSettings.chapterCacheSizeMb
            .drop(1)
            .onEach {
                val oldCache = diskCache
                currentSizeMb = it.toLong().coerceIn(10, 1000)
                diskCache = setupDiskCache(currentSizeMb)
                oldCache.close()
            }
            .launchIn(scope)
    }

    val readableSize: String
        get() = Formatter.formatFileSize(context, DiskUtil.getDirectorySize(diskCache.directory))

    fun getHtml(key: String): String? {
        return try {
            diskCache.get(DiskUtil.hashKeyForDisk(key))?.use { it.getString(0) }
        } catch (_: Exception) {
            null
        }
    }

    fun putHtml(key: String, html: String) {
        var editor: DiskLruCache.Editor? = null
        try {
            editor = diskCache.edit(DiskUtil.hashKeyForDisk(key)) ?: return
            editor.newOutputStream(0).sink().buffer().use {
                it.write(html.toByteArray())
                it.flush()
            }
            diskCache.flush()
            editor.commit()
            editor.abortUnlessCommitted()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to put novel chapter to cache" }
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    fun clear(): Int {
        return try {
            val count = diskCache.directory.listFiles()?.size ?: 0
            diskCache.delete()
            diskCache = setupDiskCache(currentSizeMb)
            count
        } catch (_: Exception) {
            0
        }
    }

    private fun setupDiskCache(sizeMb: Long): DiskLruCache {
        return DiskLruCache.open(
            File(context.cacheDir, "novel_chapter_cache"),
            PARAMETER_APP_VERSION,
            PARAMETER_VALUE_COUNT,
            sizeMb * 1024 * 1024,
        )
    }

    companion object {
        fun cacheKey(sourceId: Long, novelUrl: String, chapterUrl: String): String {
            return "$sourceId\n$novelUrl\n$chapterUrl"
        }

        private const val DEFAULT_SIZE_MB = 100L
        private const val PARAMETER_APP_VERSION = 1
        private const val PARAMETER_VALUE_COUNT = 1
    }
}
