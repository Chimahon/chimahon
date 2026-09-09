package chimahon.novel.data

import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubParseException
import chimahon.novel.data.epub.EpubParser
import chimahon.novel.source.LocalNovelFiles
import eu.kanade.tachiyomi.util.lang.Hash
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object BookStorage {

    fun getBooksDirectory(context: android.content.Context): File {
        return File(context.filesDir, "novels")
    }

    /**
     * Public local-novels root (`<base>/localnovel/`, manga `<base>/local/`
     * mirror), set by the app at startup. Reader-cache books (`src_*`) always
     * stay private; local books resolve public-first, private-legacy second.
     */
    @Volatile var localBooksRoot: File? = null

    fun loadEpub(directory: File): EpubBook {
        deleteObsoleteSpineCache(directory)
        return EpubParser.parse(directory)
    }

    fun getBookDirectory(context: android.content.Context, bookId: String): File {
        if (bookId.startsWith("src_")) return File(getBooksDirectory(context), bookId)
        localBooksRoot?.let { root ->
            val pub = File(root, bookId)
            if (pub.isDirectory) return pub
        }
        val priv = File(getBooksDirectory(context), bookId)
        if (priv.isDirectory) return priv
        // Extracted-cache copy (manga/anime parity): readable content wins over a
        // bare path, so every isDirectory consumer sees extracted books.
        // Extraction itself only happens via ensureReadableDir, never here.
        LocalNovelFiles.extractedCacheDir(context, bookId)
            .takeIf { hasImportedBookContent(it) }
            ?.let { return it }
        return localBooksRoot?.let { File(it, bookId) } ?: priv
    }

    /**
     * Unique `<Title>` dir for a new import (manga local mirror: the folder is
     * the identity). Reuses the dir when the same book is reimported, matched
     * through the DB row instead of sidecar metadata.
     */
    suspend fun uniqueTitleDir(root: File, title: String, author: String): File {
        val base = sanitizeFileName(title.ifBlank { "Unknown" })
        val plain = File(root, base)
        if (!plain.exists()) return plain
        val sameBook = runCatching {
            Injekt.get<NovelRepository>()
                .getNovelByLocalFolder(plain.name)
                ?.author?.trim().equals(author.trim(), ignoreCase = true)
        }.getOrDefault(false)
        if (sameBook) return plain
        var n = 1
        var dir = File(root, "$base ($n)")
        while (dir.exists()) {
            n++
            dir = File(root, "$base ($n)")
        }
        return dir
    }

    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{C}]"), "_").trim().trim('.', ' ')
        return clean.ifBlank { "Unknown" }.take(120)
    }

    fun copyFile(from: File, to: File): File {
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        return to
    }

    fun delete(file: File): Boolean {
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    inline fun <reified T> save(`object`: T, directory: File, fileName: String) where T : Any {
        val targetFile = File(directory, fileName)
        directory.mkdirs()
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        val data = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<T>(),
            `object`,
        )
        targetFile.writeText(data)
        // Reader hot path uses save() directly (not saveBookmark/saveMetadata).
        when (fileName) {
            FileNames.bookmark, FileNames.metadata, FileNames.bookinfo ->
                chimahon.widget.ImmersionWidgetSignals.notifyNovelsChanged()
            FileNames.statistics ->
                chimahon.widget.ImmersionWidgetSignals.notifyStatsChanged()
        }
    }

    inline fun <reified T> load(directory: File, fileName: String): T? {
        val file = File(directory, fileName)
        if (!file.exists()) return null
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val content = file.readText()
            json.decodeFromString<T>(content)
        } catch (e: Exception) {
            null
        }
    }
    fun saveBookmark(bookmark: Bookmark, directory: File) {
        save(bookmark, directory, FileNames.bookmark)
    }

    fun loadBookmark(directory: File): Bookmark? {
        return load(directory, FileNames.bookmark)
    }

    fun saveMetadata(metadata: BookMetadata, directory: File) {
        save(metadata, directory, FileNames.metadata)
    }

    fun loadMetadata(directory: File): BookMetadata? {
        return load(directory, FileNames.metadata)
    }

    fun saveStatistics(statistics: List<Statistics>, directory: File) {
        save(statistics, directory, FileNames.statistics)
    }

    fun loadStatistics(directory: File): List<Statistics>? {
        return load(directory, FileNames.statistics)
    }

    fun loadAllBooks(context: android.content.Context): List<BookMetadata> {
        // Re-resolve every scan: the cached root is startup-only, so a
        // changed storage base (or a first resolution that failed) would
        // otherwise blind File consumers to the public folder forever.
        LocalNovelFiles.publicRoot(context)?.let { localBooksRoot = it }
        val rowsByFolder = runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                Injekt.get<NovelRepository>().getAll()
            }
        }.getOrNull()?.mapNotNull { row ->
            row.localFolder?.takeIf { it.isNotBlank() }?.let { it to row }
        }?.toMap().orEmpty()
        // UniFile-first (manga/anime SAF parity): the private dir scans as
        // Files, the public dir always as a document tree — one listing for
        // file and tree schemes alike, no scheme branches downstream.
        val fileStored = loadStoredBookDirs(getBooksDirectory(context), rowsByFolder)
        val covered = fileStored.map { it.metadata.id }.toSet()
        // Public readability = has `.epub` (public holds no extracted
        // trees, so no content walks — one shallow census per scan).
        val epubFolders = LocalNovelFiles.foldersWithEpub(context)
        val uniStored = LocalNovelFiles.publicRootUni(context)?.let { uniRoot ->
            loadStoredBookUniDirs(context, uniRoot, rowsByFolder, covered, epubFolders)
        }.orEmpty()
        return (fileStored + uniStored)
            .groupBy { bookIdentityKey(it.metadata) }
            .map { (_, dupes) ->
                if (dupes.size == 1) {
                    dupes.first().metadata
                } else {
                    dupes.minWith(
                        compareBy<StoredBookDir>(
                            { !it.hasContent() },
                            { it.metadata.id != bookIdentityKey(it.metadata) },
                            { -it.metadata.lastAccess },
                        ),
                    ).metadata
                }
            }
    }

    fun bookIdentityKey(metadata: BookMetadata): String {
        val titleKey = metadata.title?.trim()?.lowercase().orEmpty()
        val authorKey = metadata.author?.trim()?.lowercase().orEmpty()
        if (titleKey.isNotEmpty() || authorKey.isNotEmpty()) {
            return Hash.md5("$titleKey|$authorKey")
        }

        metadata.hash?.takeIf { it.isNotBlank() }?.let { return it }

        return metadata.id
    }

    fun hasImportedBookContent(directory: File): Boolean {
        val contentExtensions = setOf("opf", "xhtml", "html", "htm", "ncx")
        return directory.walkTopDown().any { file ->
            file.isFile && file.extension.lowercase() in contentExtensions
        }
    }

    /**
     * Drop extraction-cache dirs with no live book (files deleted outside the
     * app orphan these; in-app deletes wipe their own) plus stale import
     * temps from crashed imports. `liveFolders` = scan ids + row folders.
     * Missing originals re-extract on demand, so over-pruning self-heals.
     */
    fun pruneCaches(context: android.content.Context, liveFolders: Set<String>) {
        runCatching {
            LocalNovelFiles.extractedCacheRoot(context).listFiles()
                ?.filter { it.isDirectory && it.name !in liveFolders }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
        runCatching {
            // An in-flight import is never an hour old; older = crash debris.
            val cutoff = System.currentTimeMillis() - 3_600_000L
            context.cacheDir.listFiles()
                ?.filter {
                    (it.name.startsWith("import_") || it.name.startsWith("temp_extract_")) &&
                        it.lastModified() < cutoff
                }
                ?.forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
        }
    }

    fun deleteBook(context: android.content.Context, bookId: String): Boolean {        var deletedAny = false
        // Public original first, UniFile-first (one path for file and tree
        // schemes alike).
        runCatching { LocalNovelFiles.publicRootUni(context)?.findFile(bookId) }
            .getOrNull()?.takeIf { it.isDirectory }?.let { uni ->
                if (runCatching { uni.delete() }.getOrDefault(false)) deletedAny = true
            }
        // Private/File copies. getBookDirectory is extraction-aware, but a
        // File-public hit shadows the extracted cache, so it goes explicitly below.
        val bookDir = getBookDirectory(context, bookId)
        // The scan is row-backed, so the identity key resolves titles
        // without touching sidecars.
        val duplicateDirs = runCatching {
            val books = loadAllBooks(context)
            val targetKey = books.firstOrNull { it.id == bookId }?.let { bookIdentityKey(it) }
                ?: return@runCatching emptyList()
            books.filter { bookIdentityKey(it) == targetKey }
                .map { getBookDirectory(context, it.id) }
        }.getOrNull().orEmpty()

        (duplicateDirs + bookDir).distinctBy { it.absolutePath }
            .filter { it.exists() }
            .forEach { if (delete(it)) deletedAny = true }
        LocalNovelFiles.extractedCacheDir(context, bookId).takeIf { it.exists() }?.let {
            if (delete(it)) deletedAny = true
        }
        if (deletedAny) {
            chimahon.widget.ImmersionWidgetSignals.notifyNovelsChanged()
        }
        return deletedAny
    }

    private fun loadStoredBookDirs(
        booksDir: File,
        rowsByFolder: Map<String, tachiyomi.domain.novel.model.Novel>,
    ): List<StoredBookDir> {
        if (!booksDir.exists()) return emptyList()
        // Rows carry identity for registered books (imports no longer write
        // sidecars). Legacy sidecar second, minimal synthetic last so orphan
        // dirs still surface instead of vanishing.
        return booksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { bookDir ->
                val metadata = rowsByFolder[bookDir.name]?.let { BookMetadata.fromRow(it, bookDir.name) }
                    ?: loadMetadata(bookDir)?.copy(
                        id = bookDir.name,
                        folder = bookDir.name,
                    )
                    // Cache shells, temp and backup dirs are never library
                    // entries; orphans surface minimally instead of vanishing.
                    ?: if (bookDir.name.startsWith("src_") ||
                        bookDir.name.endsWith(".tmp") ||
                        bookDir.name.endsWith(".bak")
                    ) {
                        return@mapNotNull null
                    } else {
                        BookMetadata(id = bookDir.name, folder = bookDir.name)
                    }
                StoredBookDir(bookDir, metadata)
            }
            .orEmpty()
    }

    /**
     * Document-tree twin of [loadStoredBookDirs] (manga/anime SAF parity):
     * same row → legacy sidecar → synthetic order, sidecars read as streams.
     * Runs on every scan (the public dir is always enumerated as a document
     * tree); names covered by the private File scan are skipped.
     */
    private fun loadStoredBookUniDirs(
        context: android.content.Context,
        rootUni: com.hippo.unifile.UniFile,
        rowsByFolder: Map<String, tachiyomi.domain.novel.model.Novel>,
        coveredNames: Set<String>,
        epubFolders: Set<String>,
    ): List<StoredBookDir> {
        return runCatching { rootUni.listFiles() }.getOrNull()
            ?.filter { it.isDirectory }
            ?.mapNotNull { uniDir ->
                val name = uniDir.name ?: return@mapNotNull null
                if (name in coveredNames) return@mapNotNull null
                if (name.startsWith("src_") || name.endsWith(".tmp") || name.endsWith(".bak")) {
                    return@mapNotNull null
                }
                val metadata = rowsByFolder[name]?.let { BookMetadata.fromRow(it, name) }
                    ?: loadUniMetadata(context, uniDir, name)
                    ?: BookMetadata(id = name, folder = name)
                StoredBookDir(
                    directory = null,
                    metadata = metadata,
                    uniHasContent = name in epubFolders,
                )
            }
            .orEmpty()
    }

    private fun loadUniMetadata(
        context: android.content.Context,
        uniDir: com.hippo.unifile.UniFile,
        name: String,
    ): BookMetadata? {
        val text = LocalNovelFiles.readSidecarText(
            context.contentResolver, uniDir, FileNames.metadata,
        ) ?: return null
        return try {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<BookMetadata>(text)
                .copy(id = name, folder = name)
        } catch (_: Exception) {
            null
        }
    }

    private data class StoredBookDir(
        val directory: File?,
        val metadata: BookMetadata,
        val uniHasContent: Boolean = false,
    ) {
        fun hasContent(): Boolean =
            (directory?.let { hasImportedBookContent(it) } == true) || uniHasContent
    }

    private fun deleteObsoleteSpineCache(directory: File) {
        File(directory, "spine_cache.json")
            .takeIf { it.exists() }
            ?.delete()
    }

    enum class BookStorageError {
        ACCESS_DENIED,
        DOCUMENTS_DIRECTORY_NOT_FOUND,
        EPUB_IMPORT_FAILED,
        ;

        fun message(): String = when (this) {
            ACCESS_DENIED -> "Could not access .epub file"
            DOCUMENTS_DIRECTORY_NOT_FOUND -> "Documents directory not found"
            EPUB_IMPORT_FAILED -> "Could not import .epub file"
        }
    }
}
