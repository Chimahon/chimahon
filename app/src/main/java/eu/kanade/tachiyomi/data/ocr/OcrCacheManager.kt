package chimahon.ocr

import android.content.Context
import android.text.format.Formatter
import chimahon.ocr.OcrBlockData
import chimahon.ocr.OcrPageData
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val CURRENT_VERSION = 2

/**
 * Manages OCR cache storage with dual locations:
 * - Downloaded chapters: {downloads}/Tachiyomi/Source/Manga/Chapter/.ocr_cache.json or .ocr.json sidecar
 * - Online chapters: {filesDir}/ocr_cache/{sourceId}/{mangaId}/{chapterId}.json
 *
 * Storage format:
 * - For directory chapters: /Downloads/Tachiyomi/Source/Manga/Chapter/.ocr_cache.json
 * - For CBZ chapters: /Downloads/Tachiyomi/Source/Manga/Chapter.cbz.ocr.json (sidecar file)
 */
class OcrCacheManager(
    private val context: Context,
    private val json: Json,
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) {
    private val mutex = Mutex()

    companion object {
        private const val OCR_CACHE_FILE = "ocr_cache.json"
        private const val LEGACY_OCR_CACHE_FILE = ".ocr_cache.json"
        private const val OCR_SIDECAR_SUFFIX = ".ocr.json"
        private const val CURRENT_VERSION = 2
        private const val INTERNAL_OCR_DIR = "ocr_cache"
    }

    /**
     * Safely write JSON to a file using UniFile output stream.
     */
    private fun writeJson(targetFile: UniFile, jsonString: String) {
        try {
            targetFile.openOutputStream().bufferedWriter().use {
                it.write(jsonString)
                it.flush()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: Failed to write OCR cache file ${targetFile.name}" }
            throw e
        }
    }

    /**
     * Read and parse OCR data from a file, returning empty data if blank or corrupt.
     */
    private fun readOcrData(cacheFile: UniFile): OcrChapterData {
        return try {
            val content = cacheFile.openInputStream().bufferedReader().use { it.readText() }
            if (content.isBlank()) {
                OcrChapterData(pages = emptyMap(), version = CURRENT_VERSION)
            } else {
                json.decodeFromString<OcrChapterData>(content)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrCache: Corrupt or unreadable cache file ${cacheFile.name}, resetting" }
            OcrChapterData(pages = emptyMap(), version = CURRENT_VERSION)
        }
    }

    private fun hasNormalOcrPages(cacheFile: UniFile?): Boolean {
        if (cacheFile?.exists() != true) return false
        return readOcrData(cacheFile).pages.isNotEmpty()
    }

    /**
     * Save OCR blocks for a single page to the chapter's OCR cache file.
     */
    suspend fun saveOcrBlocks(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        cacheVariant: String? = null,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chapterLocation = findChapterLocation(manga, chapter, source)
            val isDownloaded = isChapterDownloaded(manga, chapter, source)

            when {
                isDownloaded && chapterLocation != null -> {
                    when (chapterLocation) {
                        is ChapterLocation.Directory -> {
                            saveToDirectory(chapterLocation.dir, pageIndex, blocks, language, cacheVariant)
                        }
                        is ChapterLocation.Cbz -> {
                            saveToCbz(chapterLocation.file, pageIndex, blocks, language, cacheVariant)
                        }
                    }
                }
                else -> {
                    saveToInternal(manga, chapter, source, pageIndex, blocks, language, cacheVariant)
                }
            }
        }
    }

    /**
     * Load OCR blocks for a single page from cache.
     */
    suspend fun loadOcrBlocks(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        cacheVariant: String? = null,
    ): List<OcrTextBlock>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chapterLocation = findChapterLocation(manga, chapter, source)
            val isDownloaded = isChapterDownloaded(manga, chapter, source)

            val downloadBlocks = if (chapterLocation != null && isDownloaded) {
                when (chapterLocation) {
                    is ChapterLocation.Directory -> loadFromDirectory(chapterLocation.dir, pageIndex, cacheVariant)
                    is ChapterLocation.Cbz -> loadFromCbz(chapterLocation.file, pageIndex, cacheVariant)
                }
            } else {
                null
            }

            if (downloadBlocks != null) {
                return@withLock downloadBlocks
            }

            loadFromInternal(manga, chapter, source, pageIndex, cacheVariant)
        }
    }

    /**
     * Check if OCR data exists for a chapter.
     */
    suspend fun hasOcrData(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chapterLocation = findChapterLocation(manga, chapter, source)
            val isDownloaded = isChapterDownloaded(manga, chapter, source)

            val downloadCacheFile = if (chapterLocation != null && isDownloaded) {
                when (chapterLocation) {
                    is ChapterLocation.Directory -> findDirectoryCacheFile(chapterLocation.dir)
                    is ChapterLocation.Cbz -> findSidecarFile(chapterLocation.file)
                }
            } else {
                null
            }

            hasNormalOcrPages(downloadCacheFile) ||
                hasNormalOcrPages(UniFile.fromFile(getInternalCacheFile(manga, chapter, source)))
        }
    }

    /**
     * Delete OCR data for a chapter.
     */
    suspend fun deleteOcrForChapter(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chapterLocation = findChapterLocation(manga, chapter, source)
            val isDownloaded = isChapterDownloaded(manga, chapter, source)

            if (chapterLocation != null && isDownloaded) {
                when (chapterLocation) {
                    is ChapterLocation.Directory -> {
                        chapterLocation.dir.findFile(OCR_CACHE_FILE)?.delete()
                        chapterLocation.dir.findFile(LEGACY_OCR_CACHE_FILE)?.delete()
                    }
                    is ChapterLocation.Cbz -> {
                        findSidecarFile(chapterLocation.file)?.delete()
                    }
                }
            }

            getInternalCacheFile(manga, chapter, source).delete()
        }
    }

    /**
     * Delete all OCR data for a manga.
     */
    suspend fun deleteOcrForManga(
        manga: Manga,
        source: Source,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val mangaDir = if (source.isLocal()) {
                storageManager.getLocalSourceDirectory()?.findFile(manga.url)
                    ?: downloadProvider.findMangaDir(manga.ogTitle, source)
            } else {
                downloadProvider.findMangaDir(manga.ogTitle, source)
            }
            mangaDir?.listFiles()?.forEach { chapterEntry ->
                when {
                    chapterEntry.isDirectory -> {
                        chapterEntry.findFile(OCR_CACHE_FILE)?.delete()
                        chapterEntry.findFile(LEGACY_OCR_CACHE_FILE)?.delete()
                    }
                    chapterEntry.isFile -> {
                        val name = chapterEntry.name.orEmpty()
                        if (name.endsWith(OCR_SIDECAR_SUFFIX)) {
                            chapterEntry.delete()
                        } else {
                            chapterEntry.parentFile?.findFile("$name$OCR_SIDECAR_SUFFIX")?.delete()
                        }
                    }
                }
            }

            val internalDir = java.io.File(getInternalOcrDir(), source.id.toString())
            val mangaCacheDir = java.io.File(internalDir, manga.id.toString())
            mangaCacheDir.deleteRecursively()
        }
    }

    /**
     * Get total OCR cache size from internal storage.
     */
    suspend fun getReadableSize(): String = withContext(Dispatchers.IO) {
        var totalSize = 0L

        val internalDir = getInternalOcrDir()
        internalDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalSize += file.length()
            }
        }

        Formatter.formatFileSize(context, totalSize)
    }

    /**
     * Clear all OCR cache from internal storage.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            getInternalOcrDir().deleteRecursively()
        }
    }

    // ========== Private helpers ==========

    private fun isChapterDownloaded(manga: Manga, chapter: Chapter, source: Source): Boolean {
        if (source.isLocal()) return true
        return downloadManager.isChapterDownloaded(
            chapterName = chapter.name,
            chapterScanlator = chapter.scanlator,
            chapterUrl = chapter.url,
            mangaTitle = manga.ogTitle,
            sourceId = source.id,
        )
    }

    private sealed class ChapterLocation {
        data class Directory(val dir: UniFile) : ChapterLocation()
        data class Cbz(val file: UniFile) : ChapterLocation()
    }

    private fun findChapterLocation(manga: Manga, chapter: Chapter, source: Source): ChapterLocation? {
        val chapterDir = if (source.isLocal()) {
            val splitUrl = chapter.url.split('/', limit = 2)
            splitUrl.takeIf { it.size == 2 }?.let { (mangaDirName, chapterName) ->
                storageManager.getLocalSourceDirectory()?.findFile(mangaDirName)?.findFile(chapterName)
            }
        } else {
            null
        } ?: downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.ogTitle,
            source,
        ) ?: return null

        return when {
            chapterDir.isDirectory -> ChapterLocation.Directory(chapterDir)
            chapterDir.isFile -> ChapterLocation.Cbz(chapterDir)
            else -> {
                logcat(LogPriority.WARN) { "OcrCache: Unknown chapter format: ${chapterDir.name}" }
                null
            }
        }
    }

    private fun findDirectoryCacheFile(dir: UniFile): UniFile? =
        dir.findFile(OCR_CACHE_FILE) ?: dir.findFile(LEGACY_OCR_CACHE_FILE)

    /**
     * Save OCR data to a directory-based chapter.
     */
    private fun saveToDirectory(
        dir: UniFile,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        cacheVariant: String?,
    ) {
        val existingFile = findDirectoryCacheFile(dir)
        val cacheFile = dir.findFile(OCR_CACHE_FILE) ?: dir.createFile(OCR_CACHE_FILE)
        if (cacheFile == null) {
            logcat(LogPriority.ERROR) { "OcrCache: Failed to create cache file in directory" }
            return
        }

        val chapterData = if (existingFile != null) readOcrData(existingFile) else OcrChapterData(pages = emptyMap(), version = CURRENT_VERSION)

        val pageData = OcrPageData(
            blocks = blocks.map { it.toBlockData() },
            language = language,
            version = CURRENT_VERSION,
        )

        val newData = chapterData.withPage(pageIndex, pageData, cacheVariant)
        writeJson(cacheFile, json.encodeToString(newData))
        if (existingFile?.name == LEGACY_OCR_CACHE_FILE) {
            existingFile.delete()
        }
    }

    /**
     * Load OCR data from a directory-based chapter.
     */
    private fun loadFromDirectory(dir: UniFile, pageIndex: Int, cacheVariant: String?): List<OcrTextBlock>? {
        val cacheFile = findDirectoryCacheFile(dir) ?: return null

        return try {
            val content = cacheFile.openInputStream().bufferedReader().use { it.readText() }
            if (content.isBlank()) {
                logcat(LogPriority.WARN) { "OcrCache: Directory cache file is empty" }
                return null
            }
            val chapterData = json.decodeFromString<OcrChapterData>(content)
            chapterData.page(pageIndex, cacheVariant)?.blocks?.map { it.toTextBlock() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: Failed to load from directory" }
            null
        }
    }

    /**
     * Get or create the sidecar file for a CBZ.
     * Sidecar file: Chapter.cbz -> Chapter.cbz.ocr.json
     */
    private fun getSidecarFile(cbzFile: UniFile): UniFile? {
        val parent = cbzFile.parentFile ?: return null
        val sidecarName = "${cbzFile.name}$OCR_SIDECAR_SUFFIX"
        return parent.findFile(sidecarName) ?: parent.createFile(sidecarName)
    }

    private fun findSidecarFile(cbzFile: UniFile): UniFile? {
        val parent = cbzFile.parentFile ?: return null
        return parent.findFile("${cbzFile.name}$OCR_SIDECAR_SUFFIX")
    }

    /**
     * Save OCR data to a CBZ sidecar file.
     */
    private fun saveToCbz(
        cbzFile: UniFile,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        cacheVariant: String?,
    ) {
        val sidecarFile = getSidecarFile(cbzFile)
        if (sidecarFile == null) {
            logcat(LogPriority.ERROR) { "OcrCache: Failed to create sidecar file for CBZ" }
            return
        }

        val chapterData = readOcrData(sidecarFile)

        val pageData = OcrPageData(
            blocks = blocks.map { it.toBlockData() },
            language = language,
            version = CURRENT_VERSION,
        )

        val newData = chapterData.withPage(pageIndex, pageData, cacheVariant)
        writeJson(sidecarFile, json.encodeToString(newData))
    }

    /**
     * Load OCR data from a CBZ sidecar file.
     */
    private fun loadFromCbz(cbzFile: UniFile, pageIndex: Int, cacheVariant: String?): List<OcrTextBlock>? {
        val sidecarFile = findSidecarFile(cbzFile) ?: return null

        return try {
            val content = sidecarFile.openInputStream().bufferedReader().use { it.readText() }
            if (content.isBlank()) {
                logcat(LogPriority.WARN) { "OcrCache: CBZ sidecar file is empty" }
                return null
            }
            val chapterData = json.decodeFromString<OcrChapterData>(content)
            chapterData.page(pageIndex, cacheVariant)?.blocks?.map { it.toTextBlock() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: Failed to load from CBZ sidecar" }
            null
        }
    }

    /**
     * Get the internal storage directory for OCR cache.
     */
    private fun getInternalOcrDir(): java.io.File {
        val dir = java.io.File(context.filesDir, INTERNAL_OCR_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Get the cache file path for an online chapter.
     * Path: {filesDir}/ocr_cache/{sourceId}/{mangaId}/{chapterId}.json
     */
    private fun getInternalCacheFile(manga: Manga, chapter: Chapter, source: Source): java.io.File {
        val baseDir = getInternalOcrDir()
        val sourceDir = java.io.File(baseDir, source.id.toString())
        val mangaDir = java.io.File(sourceDir, manga.id.toString())
        if (!mangaDir.exists()) {
            mangaDir.mkdirs()
        }
        return java.io.File(mangaDir, "${chapter.id}.json")
    }

    /**
     * Save OCR data to internal storage (for online chapters).
     */
    private fun saveToInternal(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
        cacheVariant: String?,
    ) {
        val cacheFile = getInternalCacheFile(manga, chapter, source)

        val chapterData = if (cacheFile.exists()) {
            val uniFile = com.hippo.unifile.UniFile.fromFile(cacheFile)
            if (uniFile != null) readOcrData(uniFile) else OcrChapterData(pages = emptyMap(), version = CURRENT_VERSION)
        } else {
            OcrChapterData(pages = emptyMap(), version = CURRENT_VERSION)
        }

        val pageData = OcrPageData(
            blocks = blocks.map { it.toBlockData() },
            language = language,
            version = CURRENT_VERSION,
        )

        val newData = chapterData.withPage(pageIndex, pageData, cacheVariant)
        try {
            cacheFile.writeText(json.encodeToString(newData))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: Failed to save to internal storage" }
        }
    }

    /**
     * Load OCR data from internal storage (for online chapters).
     */
    private fun loadFromInternal(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        cacheVariant: String?,
    ): List<OcrTextBlock>? {
        val cacheFile = getInternalCacheFile(manga, chapter, source)
        if (!cacheFile.exists()) {
            return null
        }

        return try {
            val content = cacheFile.readText()
            if (content.isBlank()) {
                logcat(LogPriority.WARN) { "OcrCache: Internal cache file is empty" }
                return null
            }
            val chapterData = json.decodeFromString<OcrChapterData>(content)
            chapterData.page(pageIndex, cacheVariant)?.blocks?.map { it.toTextBlock() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrCache: Failed to load from internal storage" }
            null
        }
    }

    private fun OcrTextBlock.toBlockData() = OcrBlockData(
        xmin = xmin,
        ymin = ymin,
        xmax = xmax,
        ymax = ymax,
        lines = lines,
        vertical = vertical,
        lineGeometries = lineGeometries?.map { lg ->
            chimahon.ocr.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
        },
        language = language,
    )

    private fun OcrBlockData.toTextBlock() = OcrTextBlock(
        xmin = xmin,
        ymin = ymin,
        xmax = xmax,
        ymax = ymax,
        lines = lines,
        vertical = vertical,
        lineGeometries = lineGeometries?.map { lg ->
            chimahon.ocr.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
        },
        language = language,
    )

    // Legacy methods for backward compatibility during migration
    @Deprecated("Use new method with Manga, Chapter, Source parameters")
    suspend fun saveOcrBlocks(
        mangaId: Long,
        chapterUrl: String,
        pageIndex: Int,
        blocks: List<OcrTextBlock>,
        language: String,
    ) {
        // Legacy - no-op, will be removed
    }

    @Deprecated("Use new method with Manga, Chapter, Source parameters")
    suspend fun loadOcrBlocks(
        mangaId: Long,
        chapterUrl: String,
        pageIndex: Int,
    ): List<OcrTextBlock>? = null

    @Deprecated("Use new method with Manga, Chapter, Source parameters")
    suspend fun deleteOcrForChapter(mangaId: Long, chapterUrl: String) {
        // Legacy - no-op
    }

    @Deprecated("Use new method with Manga, Chapter, Source parameters")
    suspend fun deleteOcrForManga(mangaId: Long) {
        // Legacy - no-op
    }
}

/**
 * Data class for storing all OCR data for a chapter in a single file.
 */
@Serializable
data class OcrChapterData(
    val pages: Map<Int, OcrPageData>,
    val variants: Map<String, Map<Int, OcrPageData>> = emptyMap(),
    val version: Int = 2,
)

private fun OcrChapterData.page(pageIndex: Int, cacheVariant: String?): OcrPageData? {
    return if (cacheVariant == null) pages[pageIndex] else variants[cacheVariant]?.get(pageIndex)
}

private fun OcrChapterData.withPage(
    pageIndex: Int,
    pageData: OcrPageData,
    cacheVariant: String?,
): OcrChapterData {
    if (cacheVariant == null) return copy(pages = pages + (pageIndex to pageData))

    val variantPages = variants[cacheVariant].orEmpty() + (pageIndex to pageData)
    return copy(variants = variants + (cacheVariant to variantPages))
}
