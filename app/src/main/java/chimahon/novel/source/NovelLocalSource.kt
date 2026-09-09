package chimahon.novel.source

import android.content.Context
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.epub.EpubParser
import chimahon.novel.data.epub.SpineItemType
import chimahon.novel.data.epub.TocEntry
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Source for imported EPUBs: a plain source with the reserved
 * [Novel.LOCAL_SOURCE_ID] that reads extracted books from the novels
 * directory. Novel URLs are `local://<bookId>`, chapter URLs are
 * `local://<bookId>/<href>` (book ids never contain `/`, hrefs do).
 *
 * Identity and display fields come from the registered rows; the EPUB parse
 * supplies spine/TOC/content plus orphan identity and cover for books with
 * no row yet. The library opens local books in the reader directly.
 */
class NovelLocalSource(
    private val context: Context,
    private val novelRepository: NovelRepository = Injekt.get(),
) : NovelsPageSource {

    override val id: Long = Novel.LOCAL_SOURCE_ID
    override val name: String = context.stringResource(MR.strings.local_source)
    override val lang: String = "other"

    override val supportsLatest: Boolean = false

    override suspend fun getPopularNovels(page: Int): NovelPage = withContext(Dispatchers.IO) {
        NovelPage(listLocalNovels(), hasNextPage = false)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            val novels = listLocalNovels().filter { novel ->
                q.isBlank() ||
                    novel.title.contains(q, ignoreCase = true) ||
                    novel.author?.contains(q, ignoreCase = true) == true
            }
            NovelPage(novels, hasNextPage = false)
        }

    override suspend fun getLatestUpdates(page: Int): NovelPage = withContext(Dispatchers.IO) {
        NovelPage(listLocalNovels(), hasNextPage = false)
    }

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel = withContext(Dispatchers.IO) {
        val stableId = stableIdOf(novel.url)
        val dir = bookDir(stableId)
        val metadata = readMetadata(novel.url)
        val book = runCatching { EpubParser.parse(dir) }.getOrNull()
        // Row-backed identity when registered; EPUB parse when orphaned —
        // without this, first-time registration has no title and aborts, so
        // new imports and manual drops would never get a row (no history,
        // no resume, no cover).
        val base = metadata ?: book?.let {
            BookMetadata(
                id = stableId,
                title = it.title,
                author = it.author,
                folder = stableId,
                lang = it.language,
            )
        } ?: return@withContext novel
        // Cover comes from the EPUB itself, not the row: at register time the
        // row has no thumbnail yet, so deriving from it always yields null
        // (the importer's parsed cover was discarded with the transient
        // metadata). Row value survives only as a fallback.
        val coverUri = book?.coverPath
            ?.let { java.io.File(dir, it) }
            ?.takeIf { it.isFile }
            ?.let { "file://${it.absolutePath}" }
            ?: base.cover?.takeIf { it.isNotBlank() }?.let { path ->
                if (path.startsWith("file://")) path else "file://$path"
            }
        toSNNovel(
            base,
            description = book?.metadata?.description,
            genre = book?.metadata?.subject,
        ).copy(thumbnail_url = coverUri)
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> =
        withContext(Dispatchers.IO) {
            val stableId = stableIdOf(novel.url)
            val bookDir = bookDir(stableId)
            val book = EpubParser.parse(bookDir)
            val titles = tocTitles(book.tableOfContents)
            // Image-only spine items (cover, color illustrations) stay in the
            // list so they remain openable — the reader renders them as image
            // pages via getChapterContent's image branch. They get TOC titles
            // when available, otherwise a distinct Illustration label.
            var illustrationCount = 0
            List(book.linearSpineItems.size) { index ->
                val href = book.getChapterHref(index)
                    ?: throw IllegalStateException("Missing spine entry $index in ${novel.title}")
                val isImage = book.linearSpineItems[index].type == SpineItemType.IMAGE_ONLY
                val fallback = if (isImage) {
                    illustrationCount++
                    "Illustration $illustrationCount"
                } else {
                    "Chapter ${index + 1}"
                }
                SNChapter(
                    url = chapterUrl(stableId, href),
                    name = titleFor(titles, href) ?: fallback,
                    chapter_number = (index + 1).toFloat(),
                )
            }
        }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent =
        withContext(Dispatchers.IO) {
            val (stableId, href) = splitChapterUrl(chapter.url)
            val file = containedFile(bookDir(stableId), href)
            if (isImageHref(href)) {
                return@withContext ChapterContent.Images(listOf(file.toURI().toString()))
            }
            val raw = file.readText()
            ChapterContent.Html(rewriteRelativeImages(raw, file.parentFile ?: bookDir(stableId)))
        }

    suspend fun listLocalBooks(): List<BookMetadata> {
        // Registered rows carry identity; row-backed metadata keeps every
        // downstream null-check behaving as before. Light existence check
        // only — never extract here (browse lists every book; extraction is
        // per-open).
        return runCatching { novelRepository.getNovelsBySourceId(Novel.LOCAL_SOURCE_ID) }
            .getOrNull().orEmpty()
            .mapNotNull { novel ->
                val folder = novel.localFolder?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!hasBookFolder(folder)) return@mapNotNull null
                BookMetadata.fromRow(novel, folder)
            }
    }

    /** File dir, extracted cache, or bare public presence — no extraction, no IO beyond exists. */
    private fun hasBookFolder(stableId: String): Boolean {
        if (BookStorage.getBookDirectory(context, stableId).isDirectory) return true
        if (LocalNovelFiles.hasExtractedCache(context, stableId)) return true
        return LocalNovelFiles.hasPublicBook(context, stableId)
    }

    private suspend fun listLocalNovels(): List<SNNovel> {
        return listLocalBooks().mapNotNull { metadata ->
            runCatching { toSNNovel(metadata) }.getOrNull()
        }
    }

    private fun toSNNovel(
        metadata: BookMetadata,
        description: String? = null,
        genre: String? = null,
    ): SNNovel {
        return SNNovel(
            url = novelUrl(metadata.id),
            title = metadata.title.orEmpty().ifBlank { metadata.id },
            author = metadata.author,
            description = description?.takeIf { it.isNotBlank() },
            genre = genre?.takeIf { it.isNotBlank() },
            thumbnail_url = metadata.cover?.takeIf { it.isNotBlank() }?.let { path ->
                if (path.startsWith("file://")) path else "file://$path"
            },
            initialized = true,
            source = id,
            lang = metadata.lang?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun readMetadata(novelUrl: String): BookMetadata? {
        val folder = stableIdOf(novelUrl)
        if (!bookDir(folder).isDirectory) return null
        val row = runCatching { novelRepository.getNovelByLocalFolder(folder) }.getOrNull()
            ?: return null
        if (!row.isLocal && row.source != Novel.LOCAL_SOURCE_ID) return null
        return BookMetadata.fromRow(row, folder)
    }

    private fun bookDir(stableId: String): File {
        require(stableId.isNotBlank()) { "Empty local book id" }
        // Epub-only public books extract to the private cache on first read
        // so every File-based consumer (parser, reader, loader) works
        // unchanged. Suspend callers only (copy + extract); the light list
        // path above never comes here.
        return LocalNovelFiles.ensureReadableDir(context, stableId)
            ?: BookStorage.getBookDirectory(context, stableId)
    }

    private fun tocTitles(entries: List<TocEntry>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun visit(list: List<TocEntry>) {
            for (entry in list) {
                entry.href?.substringBefore("#")?.takeIf { it.isNotBlank() }?.let { href ->
                    out.putIfAbsent(href, entry.label)
                }
                if (entry.children.isNotEmpty()) visit(entry.children)
            }
        }
        visit(entries)
        return out
    }

    /**
     * TOC title for a spine href. Spine hrefs carry the OPF content-dir prefix
     * (`OEBPS/Text/ch1.xhtml`) while TOC hrefs are OPF-relative
     * (`Text/ch1.xhtml`), possibly with anchors/percent-encoding, so both sides
     * are normalized and matched on the longest common trailing path.
     */
    private fun titleFor(titles: Map<String, String>, href: String): String? {
        if (titles.isEmpty()) return null
        val normToc = titles.entries.associate { (key, value) -> normHref(key) to value }
        val norm = normHref(href)
        normToc[norm]?.let { return it }
        val normSegs = norm.split("/")
        return normToc.entries.mapNotNull { (key, value) ->
            val keySegs = key.split("/")
            val common = normSegs.reversed().zip(keySegs.reversed())
                .takeWhile { (a, b) -> a == b }.size
            if (common > 0) common to value else null
        }.maxByOrNull { it.first }?.second
    }

    private fun normHref(href: String): String {
        val path = href.substringBefore("#").trim().replace('\\', '/')
        val segments = path.split("/").filter { it.isNotEmpty() }
        return segments.joinToString("/").lowercase().let { lowered ->
            runCatching { java.net.URLDecoder.decode(lowered, "UTF-8") }.getOrDefault(lowered)
        }
    }

    private fun rewriteRelativeImages(html: String, baseDir: File): String {
        if (!html.contains("<img", ignoreCase = true)) return html
        return try {
            val doc = Jsoup.parse(html)
            for (img in doc.select("img[src]")) {
                val src = img.attr("src").trim()
                if (src.isBlank() || src.startsWith("data:") || src.startsWith("file:") ||
                    src.startsWith("http://") || src.startsWith("https://")
                ) {
                    continue
                }
                val target = containedFile(baseDir, src)
                if (target.isFile) img.attr("src", target.toURI().toString())
            }
            doc.body()?.html()?.takeIf { it.isNotBlank() } ?: html
        } catch (_: Exception) {
            html
        }
    }

    private fun containedFile(baseDir: File, href: String): File {
        val base = baseDir.canonicalFile
        val file = File(base, href).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Unsafe href: $href" }
        require(file.isFile) { "Missing entry: $href" }
        return file
    }

    private fun isImageHref(href: String): Boolean {
        val lower = href.substringBefore("?").lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    companion object {
        const val LOCAL_URL_PREFIX = "local://"
        const val CHAPTER_SEPARATOR = "/"

        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".bmp")

        fun novelUrl(stableId: String): String = "$LOCAL_URL_PREFIX$stableId"

        fun stableIdOf(novelUrl: String): String {
            return novelUrl.removePrefix(LOCAL_URL_PREFIX).substringBefore(CHAPTER_SEPARATOR)
        }

        fun chapterUrl(stableId: String, href: String): String {
            return "$LOCAL_URL_PREFIX$stableId$CHAPTER_SEPARATOR$href"
        }

        fun splitChapterUrl(chapterUrl: String): Pair<String, String> {
            val rest = chapterUrl.removePrefix(LOCAL_URL_PREFIX)
            val cut = rest.indexOf(CHAPTER_SEPARATOR)
            require(cut > 0 && cut < rest.length - 1) { "Malformed local chapter url" }
            return rest.substring(0, cut) to rest.substring(cut + 1)
        }
    }
}
