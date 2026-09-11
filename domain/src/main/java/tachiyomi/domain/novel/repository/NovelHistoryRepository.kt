package tachiyomi.domain.novel.repository

import tachiyomi.domain.novel.model.NovelHistory
import tachiyomi.domain.novel.model.NovelHistoryWithRelations

interface NovelHistoryRepository {

    suspend fun getHistoryByChapterId(chapterId: Long): NovelHistory?

    suspend fun getHistoryByNovelId(novelId: Long): List<NovelHistory>

    suspend fun getLatestHistoryByNovelId(novelId: Long): NovelHistory?

    suspend fun getLatestLastReadByNovelIds(novelIds: List<Long>): Map<Long, Long>

    suspend fun getHistoryWithRelations(query: String): List<NovelHistoryWithRelations>

    fun subscribeToHistoryWithRelations(query: String): kotlinx.coroutines.flow.Flow<List<NovelHistoryWithRelations>>

    suspend fun deleteAllHistory(): Boolean

    suspend fun upsertHistory(
        chapterId: Long,
        lastRead: Long,
        timeRead: Long,
    ): Boolean

    suspend fun resetHistoryByChapterIds(chapterIds: List<Long>)

    suspend fun deleteHistoryByNovelIds(novelIds: List<Long>)

    suspend fun getTotalTimeRead(): Long
}
