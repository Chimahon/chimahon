package chimahon.novel.reader

import android.content.Context
import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubParser
import chimahon.novel.data.epub.SpineItemType
import chimahon.novel.source.LocalNovelFiles
import java.io.File

/**
 * Detects and cleans books whose text resolves blank (truncated/0-byte
 * extractions, failed migrations). Detection is conservative: image pages
 * and divider pages with any renderable content never count as blank.
 * Cleanup only removes regenerable derived content — source EPUBs and
 * unrecoverable trees are never touched — and never writes the database.
 */
fun assessTextDamage(
    document: EpubBook,
    maxSamples: Int = 5,
    readChapter: (EpubBook, Int) -> String? = { doc, index ->
        runCatching { EpubParser().parseChapter(doc, index) }.getOrNull()
    },
): Boolean {
    val items = document.linearSpineItems
    if (items.isEmpty()) return false
    var sampled = 0
    var blank = 0
    for (index in items.indices) {
        if (sampled >= maxSamples) break
        val item = items[index]
        if (item.type == SpineItemType.IMAGE_ONLY) {
            if (imagePageReadable(document, index)) continue
            sampled++
            blank++
            continue
        }
        sampled++
        if (isChapterBlank(readChapter(document, index))) blank++
    }
    return sampled > 0 && blank == sampled
}

private fun imagePageReadable(document: EpubBook, index: Int): Boolean {
    val url = document.getImageUrl(index) ?: return false
    return runCatching {
        val file = File(java.net.URI(url))
        file.isFile && file.length() > 0
    }.getOrDefault(false)
}

private fun isChapterBlank(html: String?): Boolean {
    if (html.isNullOrBlank()) return true
    if (html.contains("<img", ignoreCase = true)) return false
    val text = html.replace(Regex("<[^>]+>"), "").trim()
    return text.isEmpty()
}

data class BookCleanReport(
    val cacheDropped: Boolean,
    val treePruned: Boolean,
    val sourceEpubPresent: Boolean,
)

/**
 * Drops poisoned derived content for [bookId] so the next resolve
 * re-extracts fresh. Never deletes source EPUBs; never prunes a tree that
 * has no bundled EPUB to rebuild from; never touches the database.
 */
suspend fun cleanBrokenBookFiles(
    context: Context,
    bookId: String,
    rootDir: File,
): BookCleanReport {
    var cacheDropped = false
    var treePruned = false

    runCatching {
        val cacheDir = LocalNovelFiles.extractedCacheDir(context, bookId)
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDropped = true
        }
    }

    val fileHasEpub = rootDir.isDirectory && runCatching {
        rootDir.listFiles()?.any { it.isFile && it.extension.equals("epub", ignoreCase = true) } == true
    }.getOrDefault(false)
    val uniHasEpub = LocalNovelFiles.hasPublicBookEpub(context, bookId)
    val sourceEpubPresent = fileHasEpub || uniHasEpub

    if (rootDir.isDirectory && fileHasEpub) {
        treePruned = runCatching {
            var pruned = false
            rootDir.listFiles()
                ?.filterNot { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                ?.forEach {
                    if (runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() }.getOrDefault(false)) {
                        pruned = true
                    }
                }
            pruned
        }.getOrDefault(false)
    }

    if (!fileHasEpub) {
        runCatching {
            val uniDir = LocalNovelFiles.publicRootUni(context)?.findFile(bookId)
            val uniHasEpub = uniDir?.takeIf { it.isDirectory }?.let { dir ->
                runCatching { dir.listFiles() }.getOrNull()
                    ?.any { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true } == true
            } == true
            if (uniHasEpub && uniDir != null && uniDir.isDirectory) {
                if (LocalNovelFiles.pruneUniDirToEpubs(uniDir)) treePruned = true
            }
        }
    }

    return BookCleanReport(
        cacheDropped = cacheDropped,
        treePruned = treePruned,
        sourceEpubPresent = sourceEpubPresent,
    )
}
