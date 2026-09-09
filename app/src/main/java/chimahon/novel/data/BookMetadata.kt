package chimahon.novel.data

import kotlinx.serialization.Serializable

@Serializable
data class BookMetadata(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String? = null,
    val author: String? = null,
    val cover: String? = null,
    val folder: String? = null,
    val lastAccess: Long = System.currentTimeMillis(),
    val dateAdded: Long = System.currentTimeMillis(),
    val hash: String? = null,
    val categoryIds: List<String> = emptyList(),
    val lang: String? = null,
    val ttuFolderName: String? = null,
    val chapterStarts: List<Int>? = null,
    val novelSourceId: Long? = null,
    val novelUrl: String? = null,
    val isImported: Boolean = false,
) {
    companion object {
        /**
         * Transient view over a DB row: same shape the file metadata had,
         * built without touching JSON. Local rows map to file-backed
         * identity (null source link).
         */
        fun fromRow(novel: tachiyomi.domain.novel.model.Novel, folder: String): BookMetadata {
            val isFileBook = novel.isLocal || novel.source == tachiyomi.domain.novel.model.Novel.LOCAL_SOURCE_ID
            return BookMetadata(
                id = folder,
                title = novel.title,
                author = novel.author,
                cover = novel.thumbnailUrl,
                folder = folder,
                lastAccess = System.currentTimeMillis(),
                dateAdded = novel.dateAdded,
                hash = folder,
                categoryIds = emptyList(),
                lang = novel.lang,
                novelSourceId = if (isFileBook) null else novel.source,
                novelUrl = if (isFileBook) null else novel.url,
                isImported = isFileBook,
            )
        }
    }
}
