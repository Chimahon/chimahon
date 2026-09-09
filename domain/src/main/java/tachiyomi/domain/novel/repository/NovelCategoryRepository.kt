package tachiyomi.domain.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.novel.model.NovelCategory

interface NovelCategoryRepository {

    suspend fun getAll(): List<NovelCategory>

    fun subscribe(): Flow<List<NovelCategory>>

    suspend fun getByNovelId(novelId: Long): List<NovelCategory>

    suspend fun getCategoryIdsByNovelIds(novelIds: List<Long>): Map<Long, List<Long>>

    suspend fun create(name: String, order: Int, hidden: Boolean): Long

    suspend fun update(category: NovelCategory)

    suspend fun delete(categoryId: Long)

    suspend fun setNovelCategories(novelId: Long, categoryIds: List<Long>)

    suspend fun insertRawCategory(id: Long, name: String, order: Int, flags: Long, hidden: Boolean)
}
