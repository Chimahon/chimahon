package tachiyomi.data.novel

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novel.model.NovelCategory
import tachiyomi.domain.novel.repository.NovelCategoryRepository

fun mapNovelCategory(
    id: Long,
    name: String,
    order: Long,
    flags: Long,
    hidden: Long,
): NovelCategory = NovelCategory(
    id = id,
    name = name,
    order = order.toInt(),
    flags = flags,
    hidden = hidden != 0L,
)

class NovelCategoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelCategoryRepository {

    override suspend fun getAll(): List<NovelCategory> {
        return handler.awaitList { novel_categoriesQueries.getNovelCategories(::mapNovelCategory) }
    }

    override fun subscribe(): kotlinx.coroutines.flow.Flow<List<NovelCategory>> {
        return handler.subscribeToList { novel_categoriesQueries.getNovelCategories(::mapNovelCategory) }
    }

    override suspend fun getByNovelId(novelId: Long): List<NovelCategory> {
        return handler.awaitList {
            novel_categoriesQueries.getNovelCategoriesByNovelId(novelId, ::mapNovelCategory)
        }
    }

    override suspend fun getCategoryIdsByNovelIds(novelIds: List<Long>): Map<Long, List<Long>> {
        if (novelIds.isEmpty()) return emptyMap()
        val rows = handler.awaitList {
            novel_categoriesQueries.getNovelCategoryPairs(novelIds) { novel_id, category_id ->
                novel_id to category_id
            }
        }
        return rows.groupBy({ it.first }, { it.second })
    }

    override suspend fun create(name: String, order: Int, hidden: Boolean): Long {
        return handler.await(inTransaction = true) {
            novel_categoriesQueries.insertNovelCategory(
                name = name,
                order = order.toLong(),
                flags = 0L,
                hidden = if (hidden) 1L else 0L,
            )
            novel_categoriesQueries.selectLastInsertedNovelCategoryId().executeAsOne()
        }
    }

    override suspend fun update(category: NovelCategory) {
        handler.await {
            novel_categoriesQueries.updateNovelCategory(
                categoryId = category.id,
                name = category.name,
                order = category.order.toLong(),
                flags = category.flags,
                hidden = if (category.hidden) 1L else 0L,
            )
        }
    }

    override suspend fun delete(categoryId: Long) {
        handler.await { novel_categoriesQueries.deleteNovelCategory(categoryId) }
    }

    override suspend fun setNovelCategories(novelId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            novel_categoriesQueries.deleteNovelsCategoriesByNovelId(novelId)
            categoryIds.forEach { categoryId ->
                novel_categoriesQueries.insertNovelsCategories(novelId, categoryId)
            }
        }
    }

    override suspend fun insertRawCategory(id: Long, name: String, order: Int, flags: Long, hidden: Boolean) {
        handler.await {
            novel_categoriesQueries.insertRawNovelCategory(
                id = id,
                name = name,
                sort = order.toLong(),
                flags = flags,
                hidden = if (hidden) 1L else 0L,
            )
        }
    }
}
