package chimahon.novel.interactor

import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncNovelChapters(
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelHistoryRepository: NovelHistoryRepository = Injekt.get(),
    private val downloadManager: chimahon.novel.download.NovelDownloadManager = Injekt.get(),
) {

    data class Result(
        val newChapterCount: Int,
        val chapters: List<NovelChapter>,
    )

    suspend fun await(novelId: Long, sourceChapters: List<SNChapter>): Result {
        val now = System.currentTimeMillis()
        val dedupedSourceChapters = sourceChapters.distinctBy { it.url }
        if (dedupedSourceChapters.isEmpty()) {
            // An empty listing is a source hiccup, never truth — abort
            // before touching anything.
            return Result(
                newChapterCount = 0,
                chapters = novelChapterRepository.getChaptersByNovelId(novelId),
            )
        }
        val dbByUrl = novelChapterRepository.getChaptersByNovelId(novelId).associateBy { it.url }
        val newChapters = mutableListOf<NovelChapter>()

        dedupedSourceChapters.forEachIndexed { index, snChapter ->
            val existing = dbByUrl[snChapter.url]
            if (existing == null) {
                newChapters.add(snChapter.toDbChapter(novelId, index, now))
            } else {
                val update = NovelChapterUpdate(
                    id = existing.id,
                    novelId = novelId,
                    url = snChapter.url,
                    name = snChapter.name,
                    scanlator = snChapter.scanlator,
                    sourceOrder = index.toLong(),
                    chapterNumber = snChapter.chapter_number,
                    dateFetch = existing.dateFetch,
                    dateUpload = snChapter.date_upload,
                )
                if (existing.needsSourceMetadataUpdate(snChapter, index)) {
                    novelChapterRepository.update(update)
                }
            }
        }

        if (newChapters.isNotEmpty()) {
            novelChapterRepository.insertAll(newChapters)
        }

        // Prune rows whose URL vanished from the fresh list (e.g. image-only
        // spine items no longer listed as chapters). Rows with history or
        // downloads are kept so read state and files are never destroyed.
        val freshUrls = dedupedSourceChapters.map { it.url }.toSet()
        val historyChapterIds = novelHistoryRepository.getHistoryByNovelId(novelId)
            .map { it.chapterId }.toSet()
        dbByUrl.values
            .filter { it.url !in freshUrls && it.id !in historyChapterIds }
            .filterNot { runCatching { downloadManager.isDownloaded(it.id) }.getOrDefault(false) }
            .forEach { novelChapterRepository.deleteChapterById(it.id) }

        return Result(
            newChapterCount = newChapters.size,
            chapters = novelChapterRepository.getChaptersByNovelId(novelId),
        )
    }

    private fun SNChapter.toDbChapter(novelId: Long, index: Int, now: Long): NovelChapter {
        return NovelChapter.create().copy(
            novelId = novelId,
            url = url,
            name = name,
            scanlator = scanlator,
            sourceOrder = index.toLong(),
            chapterNumber = chapter_number,
            dateFetch = now,
            dateUpload = date_upload,
        )
    }

    private fun NovelChapter.needsSourceMetadataUpdate(chapter: SNChapter, index: Int): Boolean {
        return name != chapter.name ||
            scanlator != chapter.scanlator ||
            sourceOrder != index.toLong() ||
            chapterNumber != chapter.chapter_number ||
            dateUpload != chapter.date_upload
    }
}
