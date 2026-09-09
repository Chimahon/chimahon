package chimahon.novel.source

import android.content.ContentResolver
import android.content.Context
import chimahon.novel.data.BookImporter
import chimahon.novel.data.BookStorage
import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Public local-novels folder (`<base>/localnovel/`, manga `<base>/local/`
 * mirror), named after the novel. Falls back to the private dir when the
 * public root is unresolvable, so imports never fail.
 *
 * SAF (manga/anime parity): the storage base is a document tree that often
 * has no raw file path, so every File-based lookup has a UniFile twin.
 * Public books are `.epub`-only; extraction always lives in the private
 * cache (see [ensureExtracted]), so the whole File-based engine (parser,
 * reader, downloads) keeps working unchanged on any scheme. Cache dirs are
 * named by stableId, keeping DB/URL identity intact.
 */
object LocalNovelFiles {

    fun publicRoot(context: Context): File? = runCatching {
        val manager: StorageManager = Injekt.get()
        val uri = manager.getLocalNovelSourceDirectory()?.uri ?: return null
        if (uri.scheme != "file") return null
        val dir = File(uri.path!!)
        if (dir.isDirectory || dir.mkdirs()) dir else null
    }.getOrNull()

    /** Document-tree root with no scheme restriction (SAF twin of [publicRoot]). */
    fun publicRootUni(context: Context): UniFile? = runCatching {
        val manager: StorageManager = Injekt.get()
        manager.getLocalNovelSourceDirectory()?.takeIf { it.isDirectory }
    }.getOrNull()

    // On-disk dir keeps the legacy "staged_novels" name so existing caches
    // (and their markers) stay valid across the rename.
    fun extractedCacheRoot(context: Context): File = File(context.filesDir, "staged_novels")

    fun extractedCacheDir(context: Context, stableId: String): File =
        File(extractedCacheRoot(context), stableId)

    /** True without extracting anything: marker present. */
    fun hasExtractedCache(context: Context, stableId: String): Boolean =
        File(extractedCacheDir(context, stableId), EXTRACT_MARKER).isFile

    /**
     * The book's top-level `.epub` in the public tree, any scheme. Public
     * books are epub-only; extraction always lives in the private cache, so
     * even file-scheme books extract from here.
     */
    fun findSourceEpub(context: Context, stableId: String): UniFile? {
        if (stableId.isBlank()) return null
        val bookUni = publicRootUni(context)?.findFile(stableId)
            ?.takeIf { it.isDirectory } ?: return null
        return bookUni.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
            ?.maxByOrNull { it.length() }
    }

    /** A public book folder exists (any scheme) — light check, never extracts. */
    fun hasPublicBook(context: Context, stableId: String): Boolean {
        if (stableId.isBlank()) return false
        return publicRootUni(context)?.findFile(stableId)?.isDirectory == true
    }

    /**
     * Readable File dir for a book: directly-readable extracted content wins
     * (private imports, legacy extracted trees), otherwise the public `.epub`
     * extracts into the private cache on first call (marker-skipped after).
     * Null unless real extracted content is in hand — never a bare or
     * epub-only dir, so callers can trust the result to parse.
     */
    fun ensureReadableDir(context: Context, stableId: String): File? {
        if (stableId.isBlank()) return null
        val direct = BookStorage.getBookDirectory(context, stableId)
        if (BookStorage.hasImportedBookContent(direct)) return direct
        return ensureExtracted(context, stableId)
    }

    /**
     * Extraction cache for a public book: copy its `.epub` beside the private
     * files and extract it. Idempotent via [EXTRACT_MARKER] (epub length +
     * mtime); re-extracts when the source changes. Never throws.
     */
    fun ensureExtracted(context: Context, stableId: String): File? = runCatching {
        if (stableId.isBlank()) return null
        val extracted = extractedCacheDir(context, stableId)
        val source = findSourceEpub(context, stableId) ?: return null
        val epubDest = File(extracted, "book.epub")
        val marker = File(extracted, EXTRACT_MARKER)
        val fingerprint = "${source.length()}:${source.lastModified()}"
        val extractedOk = marker.isFile &&
            runCatching { marker.readText() }.getOrNull() == fingerprint &&
            BookStorage.hasImportedBookContent(extracted)
        if (!extractedOk) {
            extracted.mkdirs()
            // Copy via stream (uniform for file and tree schemes).
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                epubDest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // Fresh extraction: drop the old tree, unzip slip-safe, then
            // downsample oversized images (served images must never OOM).
            extracted.listFiles()
                ?.filter { it.name != "book.epub" && it.name != EXTRACT_MARKER }
                ?.forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
            BookImporter.extractZip(epubDest, extracted)
            BookImporter.normaliseTree(extracted)
            marker.writeText(fingerprint)
        }
        extracted.takeIf { BookStorage.hasImportedBookContent(it) }
    }.getOrNull()

    /**
     * Public folders holding a top-level `.epub` (once per scan — backs the
     * orphan/content checks; tapping an unstaged book extracts on demand).
     * Uni-first: one listing for file and tree schemes alike.
     */
    fun foldersWithEpub(context: Context): Set<String> {
        val uniRoot = publicRootUni(context) ?: return emptySet()
        return runCatching { uniRoot.listFiles() }.getOrNull()
            ?.filter { it.isDirectory }
            ?.filter { dir ->
                runCatching { dir.listFiles() }.getOrNull()?.any { f ->
                    f.isFile && f.name?.endsWith(".epub", ignoreCase = true) == true
                } == true
            }
            ?.mapNotNull { it.name }
            .orEmpty()
            .toSet()
    }

    /** Read a legacy sidecar JSON file from a document-tree book dir. */
    fun readSidecarText(resolver: ContentResolver, bookUni: UniFile, fileName: String): String? = runCatching {
        val file = bookUni.findFile(fileName)?.takeIf { it.isFile } ?: return null
        resolver.openInputStream(file.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    /** Unique `<Title>` dir inside a document-tree root (import collisions). */
    suspend fun uniqueTitleDirUni(rootUni: UniFile, title: String, author: String): UniFile? = runCatching {
        val base = BookStorage.sanitizeFileName(title.ifBlank { "Unknown" })
        if (rootUni.findFile(base) == null) return rootUni.createDirectory(base)
        val sameBook = runCatching {
            Injekt.get<tachiyomi.domain.novel.repository.NovelRepository>()
                .getNovelByLocalFolder(base)
                ?.author?.trim().equals(author.trim(), ignoreCase = true)
        }.getOrDefault(false)
        if (sameBook) return rootUni.findFile(base)?.takeIf { it.isDirectory }
        var n = 1
        while (true) {
            val candidate = "$base ($n)"
            if (rootUni.findFile(candidate) == null) {
                return rootUni.createDirectory(candidate)
            }
            n++
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }.getOrNull()

    // Marker filename keeps its legacy value so existing caches stay valid.
    private const val EXTRACT_MARKER = ".staged"
}
