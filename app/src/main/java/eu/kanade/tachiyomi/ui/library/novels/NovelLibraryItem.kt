package eu.kanade.tachiyomi.ui.library.novels

import com.canopus.chimareader.data.BookMetadata
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter

sealed class NovelLibraryItem {
    abstract val id: String
    abstract val title: String
    abstract val author: String?
    abstract val coverUrl: String?

    data class LocalBook(
        val metadata: BookMetadata,
        val unreadCount: Int = 0,
    ) : NovelLibraryItem() {
        override val id: String get() = metadata.id
        override val title: String get() = metadata.title ?: metadata.folder ?: "Unknown"
        override val author: String? get() = metadata.author
        override val coverUrl: String? get() = metadata.cover
    }

    data class SourceNovel(
        val novel: Novel,
        val chapters: List<NovelChapter> = emptyList(),
        val unreadCount: Int = 0,
    ) : NovelLibraryItem() {
        override val id: String get() = sourceNovelLibraryItemId(novel.id)
        override val title: String get() = novel.title
        override val author: String? get() = novel.author
        override val coverUrl: String? get() = novel.thumbnailUrl
    }
}

internal fun sourceNovelLibraryItemId(novelId: Long): String = "source_novel_$novelId"
