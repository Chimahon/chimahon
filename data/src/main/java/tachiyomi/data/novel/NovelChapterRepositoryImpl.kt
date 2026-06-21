package tachiyomi.data.novel

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository

class NovelChapterRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelChapterRepository {

    override suspend fun getChaptersByNovelId(novelId: Long): List<NovelChapter> {
        return handler.awaitList { novel_chaptersQueries.getChaptersByNovelId(novelId, NovelChapterMapper::mapChapter) }
    }

    override fun getChaptersByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>> {
        return handler.subscribeToList { novel_chaptersQueries.getChaptersByNovelId(novelId, NovelChapterMapper::mapChapter) }
    }

    override suspend fun getChapterById(id: Long): NovelChapter? {
        return handler.awaitOneOrNull { novel_chaptersQueries.getChapterById(id, NovelChapterMapper::mapChapter) }
    }

    override suspend fun getChapterByUrlAndNovelId(url: String, novelId: Long): NovelChapter? {
        return handler.awaitOneOrNull {
            novel_chaptersQueries.getChapterByUrlAndNovelId(url, novelId, NovelChapterMapper::mapChapter)
        }
    }

    override fun getUnreadChaptersByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>> {
        return handler.subscribeToList { novel_chaptersQueries.getUnreadChaptersByNovelId(novelId, NovelChapterMapper::mapChapter) }
    }

    override suspend fun insertAll(chapters: List<NovelChapter>) {
        handler.await(inTransaction = true) {
            chapters.forEach { chapter ->
                novel_chaptersQueries.insert(
                    novelId = chapter.novelId,
                    url = chapter.url,
                    name = chapter.name,
                    scanlator = chapter.scanlator,
                    read = if (chapter.read) 1L else 0L,
                    bookmark = if (chapter.bookmark) 1L else 0L,
                    lastPageRead = chapter.lastPageRead,
                    chapterNumber = chapter.chapterNumber.toDouble(),
                    sourceOrder = chapter.sourceOrder,
                    dateFetch = chapter.dateFetch,
                    dateUpload = chapter.dateUpload,
                    version = chapter.version,
                )
            }
        }
    }

    override suspend fun update(update: NovelChapterUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun deleteChaptersByNovelId(novelId: Long) {
        handler.await { novel_chaptersQueries.deleteChaptersByNovelId(novelId) }
    }

    override suspend fun removeChaptersWithNoNovel() {
        handler.await { novel_chaptersQueries.removeChaptersWithNoNovel() }
    }

    private suspend fun partialUpdate(vararg updates: NovelChapterUpdate) {
        handler.await(inTransaction = true) {
            updates.forEach { value ->
                novel_chaptersQueries.update(
                    chapterId = value.id,
                    novelId = value.novelId,
                    url = value.url,
                    name = value.name,
                    scanlator = value.scanlator,
                    read = value.read?.let { if (it) 1L else 0L },
                    bookmark = value.bookmark?.let { if (it) 1L else 0L },
                    lastPageRead = value.lastPageRead,
                    chapterNumber = value.chapterNumber?.toDouble(),
                    sourceOrder = value.sourceOrder,
                    dateFetch = value.dateFetch,
                    dateUpload = value.dateUpload,
                    version = value.version,
                )
            }
        }
    }
}
