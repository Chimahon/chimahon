package eu.kanade.tachiyomi.ui.library.novels

import chimahon.novel.data.BookMetadata
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter

sealed class NovelLibraryItem {
    abstract val id: String
    abstract val title: String
    abstract val author: String?
    abstract val coverUrl: String?
    abstract val coverLastModified: Long

    data class LocalBook(
        val metadata: BookMetadata,
    ) : NovelLibraryItem() {
        override val id: String get() = metadata.id
        override val title: String get() = metadata.title ?: metadata.folder ?: "Unknown"
        override val author: String? get() = metadata.author
        override val coverUrl: String? get() = metadata.cover
        override val coverLastModified: Long get() = metadata.coverLastModified
    }

    data class SourceNovel(
        val novel: Novel,
        val chapters: List<NovelChapter> = emptyList(),
        val unreadCount: Int = 0,
        val downloadCount: Int = 0,
    ) : NovelLibraryItem() {
        override val id: String get() = sourceNovelLibraryItemId(novel.id)
        override val title: String get() = novel.title
        override val author: String? get() = novel.author
        override val coverUrl: String? get() = novel.thumbnailUrl
        override val coverLastModified: Long get() = novel.coverLastModified
    }
}

internal fun sourceNovelLibraryItemId(novelId: Long): String = "source_novel_$novelId"
