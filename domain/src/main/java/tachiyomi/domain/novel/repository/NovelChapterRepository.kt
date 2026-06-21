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

    suspend fun insertAll(chapters: List<NovelChapter>)

    suspend fun update(update: NovelChapterUpdate): Boolean

    suspend fun deleteChaptersByNovelId(novelId: Long)

    suspend fun removeChaptersWithNoNovel()
}
