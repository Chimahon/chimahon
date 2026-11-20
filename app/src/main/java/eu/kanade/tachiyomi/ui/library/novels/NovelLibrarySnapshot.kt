package eu.kanade.tachiyomi.ui.library.novels

import chimahon.novel.data.BookMetadata
import chimahon.novel.data.NovelCategory
import tachiyomi.domain.novel.model.Novel

/** One filesystem + database read of the novel library, taken off the main thread. */
internal data class NovelLibrarySnapshot(
    val categories: List<NovelCategory>,
    val books: List<BookMetadata>,
    val sourceNovels: List<Novel>,
    val unreadCounts: Map<Long, Long>,
    val downloadCounts: Map<Long, Long>,
    val lastReadTimestamps: Map<Long, Long>,
    val novelCategoryIds: Map<Long, List<Long>>,
    val booksWithoutContent: Set<String>,
    val localNovelIds: Map<String, Long>,
    val linkedSourceNovelIds: Map<String, Long>,
    val localRows: List<Novel>,
)
