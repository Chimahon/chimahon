package tachiyomi.domain.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate

interface NovelChapterRepository {

    suspend fun getChaptersByNovelId(novelId: Long): List<NovelChapter>

    fun getChaptersByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>>

    suspend fun getChapterById(id: Long): NovelChapter?

    suspend fun getChapterByUrlAndNovelId(url: String, novelId: Long): NovelChapter?

    fun getUnreadChaptersByNovelIdAsFlow(novelId: Long): Flow<List<NovelChapter>>

    suspend fun getUnreadCountByNovelId(novelId: Long): Long

    suspend fun getUnreadCountsByNovelIds(novelIds: List<Long>): Map<Long, Long>

    suspend fun insertAll(chapters: List<NovelChapter>)

    suspend fun update(update: NovelChapterUpdate): Boolean

    /** Single-transaction bulk update (bulk mark read/unread without N awaits). */
    suspend fun updateAll(updates: List<NovelChapterUpdate>): Boolean

    suspend fun deleteChapterById(id: Long)

    suspend fun deleteChaptersByNovelId(novelId: Long)

    suspend fun removeChaptersWithNoNovel()
}
