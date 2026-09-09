package tachiyomi.data.novel

import tachiyomi.data.DatabaseHandler
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.NovelHistory
import tachiyomi.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import logcat.LogPriority

fun mapNovelHistory(
    id: Long,
    novel_chapter_id: Long,
    last_read: Long,
    time_read: Long,
): NovelHistory = NovelHistory(
    id = id,
    chapterId = novel_chapter_id,
    lastRead = last_read,
    timeRead = time_read,
)

fun mapNovelHistoryWithRelations(
    id: Long,
    novel_chapter_id: Long,
    last_read: Long,
    time_read: Long,
    novel_id: Long,
    url: String,
    name: String,
    chapter_number: Double,
    read: Long,
    progress: Double,
    title: String,
    thumbnail_url: String?,
    source: Long,
    favorite: Long,
): NovelHistoryWithRelations = NovelHistoryWithRelations(
    id = id,
    chapterId = novel_chapter_id,
    lastRead = last_read,
    timeRead = time_read,
    novelId = novel_id,
    chapterUrl = url,
    chapterName = name,
    chapterNumber = chapter_number.toFloat(),
    chapterRead = read != 0L,
    chapterProgress = progress,
    novelTitle = title,
    novelThumbnailUrl = thumbnail_url,
    novelSource = source,
    novelFavorite = favorite != 0L,
)

class NovelHistoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelHistoryRepository {

    override suspend fun getHistoryByChapterId(chapterId: Long): NovelHistory? {
        return handler.awaitOneOrNull {
            novel_historyQueries.getNovelHistoryByChapterId(chapterId, ::mapNovelHistory)
        }
    }

    override suspend fun getHistoryByNovelId(novelId: Long): List<NovelHistory> {
        return handler.awaitList {
            novel_historyQueries.getNovelHistoryByNovelId(novelId, ::mapNovelHistory)
        }
    }

    override suspend fun getLatestHistoryByNovelId(novelId: Long): NovelHistory? {
        return handler.awaitOneOrNull {
            novel_historyQueries.getLatestNovelHistoryByNovel(novelId, ::mapNovelHistory)
        }
    }

    override suspend fun getLatestLastReadByNovelIds(novelIds: List<Long>): Map<Long, Long> {
        return if (novelIds.isEmpty()) {
            emptyMap()
        } else {
            handler.awaitList {
                novel_historyQueries.getLatestLastReadByNovelIds(novelIds) { novel_id, lastRead ->
                    novel_id to lastRead
                }
            }.toMap()
        }
    }

    override suspend fun upsertHistory(
        chapterId: Long,
        lastRead: Long,
        timeRead: Long,
    ): Boolean {
        return try {
            handler.await {
                novel_historyQueries.upsertNovelHistory(
                    chapterId = chapterId,
                    lastRead = lastRead,
                    timeRead = timeRead,
                )
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun resetHistoryByChapterIds(chapterIds: List<Long>) {
        handler.await { novel_historyQueries.resetNovelHistoryByChapterIds(chapterIds) }
    }

    override suspend fun deleteHistoryByNovelIds(novelIds: List<Long>) {
        handler.await { novel_historyQueries.deleteNovelHistoryByNovelIds(novelIds) }
    }

    override suspend fun getHistoryWithRelations(query: String): List<NovelHistoryWithRelations> {
        return handler.awaitList {
            novel_historyQueries.getNovelHistoryWithRelations(query, ::mapNovelHistoryWithRelations)
        }
    }

    override suspend fun deleteAllHistory(): Boolean {
        return try {
            handler.await { novel_historyQueries.deleteAllNovelHistory() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun getTotalTimeRead(): Long {
        return handler.awaitOne { novel_historyQueries.getTotalNovelTimeRead() }
    }
}
