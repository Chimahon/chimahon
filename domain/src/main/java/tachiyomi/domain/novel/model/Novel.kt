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
    val isLocal: Boolean = false,
    val localFolder: String? = null,
    val chapterSortMode: Long = 0,
    val chapterSortDescending: Boolean = true,
    val chapterFilterUnread: Boolean = false,
    val chapterFilterBookmarked: Boolean = false,
    val lang: String? = null,
) : Serializable {

    companion object {
        const val UNKNOWN = 0L
        const val ONGOING = 1L
        const val COMPLETED = 2L
        const val LICENSED = 3L
        const val PUBLISHING_FINISHED = 4L
        const val CANCELLED = 5L
        const val ON_HIATUS = 6L

        /**
         * Reserved source id for imported/local novels; they never participate in
         * update jobs or downloads (content is already on disk).
         */
        const val LOCAL_SOURCE_ID = -1501L

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

        fun fromSourceNovel(novel: SNNovel, sourceId: Long, lang: String? = null): Novel {
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
                lang = lang?.takeIf { it.isNotBlank() }
                    ?: novel.lang?.takeIf { it.isNotBlank() },
            )
        }
    }
}

fun Novel.toSNNovel(): SNNovel {
    return SNNovel(
        url = url,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status.toInt(),
        thumbnail_url = thumbnailUrl,
        initialized = initialized,
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
    )
}
