package tachiyomi.domain.novel.model

import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import java.io.Serializable

data class Novel(
    val id: Long,
    val source: Long,
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val fetchInterval: Int,
    val dateAdded: Long,
    val coverLastModified: Long,
    val url: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: String?,
    val status: Long,
    val thumbnailUrl: String?,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val totalChapters: Int,
    val version: Long,
    val notes: String,
) : Serializable {

    val unreadCount: Int get() = 0

    companion object {
        const val UNKNOWN = 0L
        const val ONGOING = 1L
        const val COMPLETED = 2L
        const val LICENSED = 3L
        const val PUBLISHING_FINISHED = 4L
        const val CANCELLED = 5L
        const val ON_HIATUS = 6L

        fun create() = Novel(
            id = -1L,
            url = "",
            source = -1L,
            favorite = false,
            lastUpdate = 0L,
            nextUpdate = 0L,
            fetchInterval = 0,
            dateAdded = 0L,
            coverLastModified = 0L,
            title = "",
            author = null,
            artist = null,
            description = null,
            genre = null,
            status = UNKNOWN,
            thumbnailUrl = null,
            initialized = false,
            lastModifiedAt = 0L,
            favoriteModifiedAt = null,
            totalChapters = 0,
            version = 0L,
            notes = "",
        )

        fun fromSourceNovel(novel: SNNovel, sourceId: Long): Novel {
            return create().copy(
                url = novel.url,
                source = sourceId,
                title = novel.title,
                author = novel.author,
                artist = novel.artist,
                description = novel.description,
                genre = novel.genre,
                status = novel.status.toLong(),
                thumbnailUrl = novel.thumbnail_url,
                initialized = novel.initialized,
            )
        }
    }
}
