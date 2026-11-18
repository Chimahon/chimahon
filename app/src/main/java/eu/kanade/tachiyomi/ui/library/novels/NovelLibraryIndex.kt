package eu.kanade.tachiyomi.ui.library.novels

import androidx.compose.runtime.Immutable
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.NovelCategory
import tachiyomi.domain.novel.model.Novel

/**
 * Precomputed library contents: one ordered list per category instead of
 * re-filtering and re-sorting every item on each composition.
 */
@Immutable
data class NovelLibraryIndex(
    val displayedCategories: List<NovelCategory>,
    private val itemsByCategoryId: Map<String, List<NovelLibraryItem>>,
) {
    fun itemsFor(category: NovelCategory): List<NovelLibraryItem> {
        return itemsByCategoryId[category.id].orEmpty()
    }

    fun countFor(category: NovelCategory): Int {
        return itemsFor(category).size
    }

    companion object {
        val Empty = NovelLibraryIndex(emptyList(), emptyMap())
    }
}

internal data class NovelLibraryIndexInput(
    val categories: List<NovelCategory>,
    val books: List<BookMetadata>,
    val sourceNovels: List<Novel>,
    val searchQuery: String?,
    val sortMode: NovelLibraryScreenModel.SortMode,
    val sortDescending: Boolean,
    val showHiddenCategories: Boolean,
    val unreadCounts: Map<Long, Long>,
    val downloadCounts: Map<Long, Long>,
    val lastReadTimestamps: Map<Long, Long>,
    val novelCategoryIds: Map<Long, List<Long>>,
    val localNovelIds: Map<String, Long>,
    val linkedSourceNovelIds: Map<String, Long>,
)

internal fun buildNovelLibraryIndex(input: NovelLibraryIndexInput): NovelLibraryIndex {
    val query = input.searchQuery
    val localItems = input.books
        .filter { query == null || it.title?.contains(query, ignoreCase = true) == true }
        .map { NovelLibraryItem.LocalBook(it) }
    val linkedNovelIds = input.linkedSourceNovelIds.values.toSet()
    val sourceItems = input.sourceNovels
        // Local-source rows are represented by the file-scan cards above
        // (LocalBook owns MISSING/add-files/folder routing); rendering both
        // showed every import twice.
        .filterNot { it.source == Novel.LOCAL_SOURCE_ID }
        .filterNot { it.id in linkedNovelIds }
        .filter { query == null || it.title.contains(query, ignoreCase = true) }
        .map {
            NovelLibraryItem.SourceNovel(
                it,
                unreadCount = input.unreadCounts[it.id]?.toInt() ?: 0,
                downloadCount = input.downloadCounts[it.id]?.toInt() ?: 0,
            )
        }

    val knownCategoryIds = input.categories.map { it.id }.toSet()
    val allItems = localItems + sourceItems
    val itemsByCategoryId = input.categories.associate { category ->
        val comparator: Comparator<NovelLibraryItem> = when (input.sortMode) {
            NovelLibraryScreenModel.SortMode.Alphabetical -> compareBy({ it.title.lowercase() }, { it.id })
            NovelLibraryScreenModel.SortMode.DateAdded -> compareBy<NovelLibraryItem> { item ->
                when (item) {
                    is NovelLibraryItem.LocalBook -> item.metadata.dateAdded
                    is NovelLibraryItem.SourceNovel -> item.novel.dateAdded
                }
            }
            NovelLibraryScreenModel.SortMode.LastRead -> compareBy<NovelLibraryItem> { item ->
                when (item) {
                    is NovelLibraryItem.LocalBook -> item.metadata.lastAccess
                    is NovelLibraryItem.SourceNovel -> input.lastReadTimestamps[item.novel.id] ?: 0L
                }
            }
        }
        val tieBreaker = compareBy<NovelLibraryItem>({ it.title.lowercase() }, { it.id })
        val filtered = allItems.filter { it.isInCategory(category, input, knownCategoryIds) }
        category.id to if (input.sortDescending) {
            filtered.sortedWith(comparator.reversed().then(tieBreaker))
        } else {
            filtered.sortedWith(comparator.then(tieBreaker))
        }
    }

    val displayedCategories = input.categories
        .filterNot { it.isSystemCategory && itemsByCategoryId[it.id].isNullOrEmpty() }
        .filterNot { it.hidden && !input.showHiddenCategories }

    return NovelLibraryIndex(displayedCategories, itemsByCategoryId)
}

private fun NovelLibraryItem.isInCategory(
    category: NovelCategory,
    input: NovelLibraryIndexInput,
    knownCategoryIds: Set<String>,
): Boolean {
    return when (this) {
        is NovelLibraryItem.LocalBook -> {
            // Row joins win; the sidecar survives only for never-registered books.
            val novelId = input.linkedSourceNovelIds[metadata.id] ?: input.localNovelIds[metadata.id]
            if (novelId != null) {
                val rowIds = input.novelCategoryIds[novelId].orEmpty().filter { it > 0L }
                if (category.isSystemCategory) {
                    rowIds.isEmpty()
                } else {
                    val dbId = category.id.toLongOrNull()
                    dbId != null && rowIds.contains(dbId)
                }
            } else {
                val ids = metadata.normalizedCategoryIds(knownCategoryIds)
                if (category.isSystemCategory) {
                    ids.isEmpty() || ids.contains(NovelCategory.UNCATEGORIZED_ID)
                } else {
                    ids.contains(category.id)
                }
            }
        }
        is NovelLibraryItem.SourceNovel -> {
            // Absence of join rows = Default/system category (manga convention)
            val ids = input.novelCategoryIds[novel.id].orEmpty().filter { it > 0L }
            if (category.isSystemCategory) {
                ids.isEmpty()
            } else {
                val dbId = category.id.toLongOrNull()
                dbId != null && ids.contains(dbId)
            }
        }
    }
}

private fun BookMetadata.normalizedCategoryIds(knownCategoryIds: Set<String>): List<String> {
    val distinctIds = categoryIds
        .filter { it.isNotBlank() }
        .distinct()

    val nonDefaultIds = distinctIds.filterNot { it == NovelCategory.UNCATEGORIZED_ID }
    return if (nonDefaultIds.isNotEmpty()) {
        nonDefaultIds.filter { it in knownCategoryIds }
    } else {
        distinctIds
    }
}
