package chimahon.novel.reader

import android.content.Context
import chimahon.novel.cache.NovelChapterCache
import chimahon.novel.download.NovelDownloadProvider
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.detail.SourceChapterBookBuilder
import chimahon.novel.data.BookStorage
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.novel.model.toSNNovel
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Source-agnostic chapter loading: one call resolves downloads (sacred
 * files) → bounded cache → network, and either returns HTML or throws. No
 * readiness polling — callers map success to content and failure to their
 * loading/error states.
 */
class NovelChapterLoader(
    private val app: Context = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val downloadProvider: NovelDownloadProvider = Injekt.get(),
    private val htmlCache: NovelChapterCache = Injekt.get(),
) {

    suspend fun loadChapterHtml(bookId: String, spineIndex: Int, novelId: Long? = null): String =
        withContext(Dispatchers.IO) {
            val resolved = resolve(bookId, spineIndex, novelId) ?: throw IllegalStateException("Unknown chapter")
            val (source, novel, chapter) = resolved

            findDownloadedContent(source, novel.title, chapter)?.let { (raw, isHtml) ->
                return@withContext mirror(bookId, if (isHtml) buildHtml(raw) else buildText(raw))
            }
            val key = NovelChapterCache.cacheKey(source.id, novel.url, chapter.url)
            htmlCache.getHtml(key)?.let { return@withContext mirror(bookId, it) }

            val built = toXhtml(source.getChapterContent(chapter))
            htmlCache.putHtml(key, built)
            mirror(bookId, built)
        }

    private fun findDownloadedContent(
        source: NovelSource,
        novelTitle: String,
        chapter: SNChapter,
    ): Pair<String, Boolean>? {
        if (novelTitle.isBlank()) return null
        return try {
            downloadProvider.readDownloadedContent(chapter.name, novelTitle, source)
                ?.let { it.content to it.isHtml }
        } catch (_: Exception) {
            null
        }
    }

    private fun toXhtml(content: ChapterContent): String {
        if (content.isBlankContent()) throw IllegalStateException("Empty chapter")
        return SourceChapterBookBuilder.chapterContentToXhtml(content)
    }

    private fun ChapterContent.isBlankContent(): Boolean = when (this) {
        is ChapterContent.Text -> text.isBlank()
        is ChapterContent.Html -> html.isBlank()
        is ChapterContent.Images -> urls.isEmpty()
        is ChapterContent.Mixed -> items.isEmpty()
    }

    private fun buildHtml(raw: String): String =
        SourceChapterBookBuilder.buildHtmlChapterXhtml(raw)

    private fun buildText(raw: String): String =
        SourceChapterBookBuilder.buildChapterXhtml(raw)

    private suspend fun mirror(bookId: String, xhtml: String): String {
        val dir = File(BookStorage.getBookDirectory(app, bookId), SourceChapterBookBuilder.CONTENT_DIR)
            .apply { mkdirs() }
        return SourceChapterBookBuilder.mirrorChapterImages(xhtml, dir)
    }

    private data class Resolved(
        val source: NovelSource,
        val novel: SNNovel,
        val chapter: SNChapter,
    )

    private suspend fun resolve(bookId: String, spineIndex: Int, novelId: Long?): Resolved? {
        if (spineIndex < 0) return null
        // Row identity + number-ordered chapters.
        if (novelId != null) {
            val dbNovel = runCatching { novelRepository.getNovelById(novelId) }.getOrNull() ?: return null
            // Local books read their EPUB files directly.
            if (dbNovel.isLocal) return null
            val source = sourceManager.getNovelSource(dbNovel.source) ?: return null
            val row = novelChapterRepository.getChaptersByNovelId(dbNovel.id)
                .sortedBy { it.chapterNumber }
                .getOrNull(spineIndex) ?: return null
            val chapter = SNChapter(
                url = row.url,
                name = row.name,
                chapter_number = row.chapterNumber,
                date_upload = row.dateUpload,
                scanlator = row.scanlator,
            )
            return Resolved(source, dbNovel.toSNNovel(), chapter)
        }
        val bookDir = BookStorage.getBookDirectory(app, bookId)
        val metadata = BookStorage.loadMetadata(bookDir) ?: return null
        val sourceId = metadata.novelSourceId ?: return null
        val source = sourceManager.getNovelSource(sourceId) ?: return null

        // Sidecar first: works for any novel, no library needed.
        val stored = SourceChapterBookBuilder.readChapterList(bookDir)?.getOrNull(spineIndex)
        if (stored != null) {
            val novelUrl = metadata.novelUrl ?: return null
            val novel = SNNovel(url = novelUrl, title = metadata.title.orEmpty())
            val chapter = SNChapter(
                url = stored.url,
                name = stored.name,
                chapter_number = stored.number,
            )
            return Resolved(source, novel, chapter)
        }

        // Fallback: library novels resolve through the DB in reading order.
        val novelUrl = metadata.novelUrl ?: return null
        val dbNovel = novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId) ?: return null
        if (dbNovel.isLocal) return null
        val row = novelChapterRepository.getChaptersByNovelId(dbNovel.id)
            .sortedBy { it.chapterNumber }
            .getOrNull(spineIndex) ?: return null
        val chapter = SNChapter(
            url = row.url,
            name = row.name,
            chapter_number = row.chapterNumber,
            date_upload = row.dateUpload,
            scanlator = row.scanlator,
        )
        return Resolved(source, dbNovel.toSNNovel(), chapter)
    }
}
